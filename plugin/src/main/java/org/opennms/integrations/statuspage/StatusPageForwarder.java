/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2020 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2020 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.integrations.statuspage;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;
import org.opennms.integration.api.v1.alarms.AlarmLifecycleListener;
import org.opennms.integration.api.v1.config.events.AlarmType;
import org.opennms.integration.api.v1.events.EventForwarder;
import org.opennms.integration.api.v1.model.Alarm;
import org.opennms.integration.api.v1.model.DatabaseEvent;
import org.opennms.integration.api.v1.model.EventParameter;
import org.opennms.integration.api.v1.model.Severity;
import org.opennms.integration.api.v1.model.immutables.ImmutableEventParameter;
import org.opennms.integration.api.v1.model.immutables.ImmutableInMemoryEvent;
import org.opennms.statuspage.client.api.PDClient;
import org.opennms.statuspage.client.api.PDClientFactory;
import org.opennms.statuspage.client.api.PDEvent;
import org.opennms.statuspage.client.api.PDEventAction;
import org.opennms.statuspage.client.api.PDEventPayload;
import org.opennms.statuspage.client.api.PDEventSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class StatusPageForwarder implements AlarmLifecycleListener, Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(StatusPageForwarder.class);

    private static final String PD_UEI_PREFIX = "uei.opennms.org/statuspage";
    private static final String SEND_EVENT_FAILED_UEI = PD_UEI_PREFIX + "/sendEventFailed";
    private static final String SEND_EVENT_SUCCESSFUL_UEI = PD_UEI_PREFIX + "/sendEventSuccessful";

    private EventForwarder eventForwarder;
    private final PDClient pdClient;
    private final StatusPagePluginConfig pluginConfig;
    private final StatusPageServiceConfig serviceConfig;
    private final JexlExpression jexlFilterExpression;
    private final DelayQueue<StatusPageForwarderTask> taskQueue;
    private final ExecutorService executor;

    /**
     * Used to track alarms that were filtered and not forwarded to PD.
     */
    private final Set<Integer> alarmIdsFiltered = new ConcurrentSkipListSet<>();

    public StatusPageForwarder(EventForwarder eventForwarder, PDClientFactory pdClientFactory, StatusPagePluginConfig pluginConfig, StatusPageServiceConfig serviceConfig) {
        this.eventForwarder = Objects.requireNonNull(eventForwarder);
        this.pluginConfig = Objects.requireNonNull(pluginConfig);
        this.serviceConfig = Objects.requireNonNull(serviceConfig);
        pdClient = pdClientFactory.getClient();
        taskQueue = new DelayQueue<>();
        executor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("statuspage-Forwarder-" + serviceConfig.getPid() + "-%d").build());
        executor.submit(new TaskConsumer());

        if (!Strings.isNullOrEmpty(serviceConfig.getJexlFilter())) {
            JexlEngine jexl = new JexlBuilder().create();
            jexlFilterExpression = jexl.createExpression(serviceConfig.getJexlFilter());
        } else {
            jexlFilterExpression = null;
        }
    }

    private boolean shouldProcess(Alarm alarm) {
        if (alarm.getReductionKey().startsWith(PD_UEI_PREFIX)) {
            // Never forward alarms that the plugin itself creates
            return false;
        }
        if (jexlFilterExpression == null) {
            return true;
        }
        return testAlarmAgainstExpression(jexlFilterExpression, alarm);
    }

    @Override
    public void handleNewOrUpdatedAlarm(Alarm alarm) {
        if (!shouldProcess(alarm)) {
            // Remember the alarms that were filtered & not processed, so that we can skip
            // the deletes as well when we get callbacks for these
            alarmIdsFiltered.add(alarm.getId());
            return;
        }
        // We may of previously filtered the alarm, but decided to process it now
        alarmIdsFiltered.remove(alarm.getId());

        PDEvent pdEvent = toEvent(alarm);

        String reductionKey = alarm.getReductionKey();
        switch (pdEvent.getEventAction()) {
            case TRIGGER:
                enqueueTask(pdEvent, reductionKey);
                break;
            case ACKNOWLEDGE:
                ackEvent(pdEvent, reductionKey);
                break;
            case RESOLVE:
                resolveEvent(pdEvent, reductionKey);
                break;
        }
    }

    private void enqueueTask(PDEvent pdEvent, String reductionKey) {
        Duration holdDownDelay = serviceConfig.getHoldDownDelay();
        LOG.debug("Scheduling task to send event for alarm with reduction-key: {}, delay: {}", reductionKey, holdDownDelay);
        StatusPageForwarderTask task = new StatusPageForwarderTask(Instant.now().plus(holdDownDelay), reductionKey, pdEvent);
        taskQueue.offer(task);
    }

    private void resolveEvent(PDEvent pdEvent, String reductionKey) {
        LOG.debug("Resolving alarm with reduction-key: {}", reductionKey);
        if (dequeueTasks(reductionKey)) {
            return;
        }
        sendPDEvent(reductionKey, pdEvent);
    }

    private void ackEvent(PDEvent pdEvent, String reductionKey) {
        LOG.debug("Acknowledging alarm with reduction-key: {}", reductionKey);
        if (dequeueTasks(reductionKey)) {
            return;
        }
        sendPDEvent(reductionKey, pdEvent);
    }

    private boolean dequeueTasks(String reductionKey) {
        if (taskQueue.removeIf(t -> t.getReductionKey().equals(reductionKey))) {
            // This alarm wasn't sent to PD yet, and we've now cancelled that task
            LOG.debug("Task removed from queue for reduction-key: {}", reductionKey);
            return true;
        }
        return false;
    }

    private void sendPDEvent(String reductionKey, PDEvent pdEvent) {
        LOG.info("Sending event for alarm with reduction-key: {}", reductionKey);
        pdClient.sendEvent(pdEvent).whenComplete((v, ex) -> {
           if (ex != null) {
               LOG.warn("Sending event for alarm with reduction-key: {} failed.", reductionKey, ex);
               eventForwarder.sendAsync(ImmutableInMemoryEvent.newBuilder()
                       .setUei(SEND_EVENT_FAILED_UEI)
                       .setSource(StatusPageForwarder.class.getName())
                       // TODO: The API should make this be less verbose i.e.
                       // .addParameter("reductionKey", alarm.getReductionKey())
                       .addParameter(ImmutableEventParameter.newBuilder()
                               .setName("reductionKey")
                               .setValue(reductionKey)
                               .build())
                       .addParameter(ImmutableEventParameter.newBuilder()
                               .setName("message")
                               .setValue(ex.getMessage())
                               .build())
                       .addParameter(ImmutableEventParameter.newBuilder()
                               .setName("routingKey")
                               .setValue(serviceConfig.getRoutingKey())
                               .build())
                       .addParameter(ImmutableEventParameter.newBuilder()
                               .setName("pid")
                               .setValue(serviceConfig.getPid())
                               .build())
                       .build());
           } else {
               LOG.info("Event sent successfully for alarm with reduction-key: {}", reductionKey);
               eventForwarder.sendAsync(ImmutableInMemoryEvent.newBuilder()
                       .setUei(SEND_EVENT_SUCCESSFUL_UEI)
                       .setSource(StatusPageForwarder.class.getName())
                       .addParameter(ImmutableEventParameter.newBuilder()
                               .setName("reductionKey")
                               .setValue(reductionKey)
                               .build())
                       .addParameter(ImmutableEventParameter.newBuilder()
                               .setName("routingKey")
                               .setValue(serviceConfig.getRoutingKey())
                               .build())
                       .addParameter(ImmutableEventParameter.newBuilder()
                               .setName("pid")
                               .setValue(serviceConfig.getPid())
                               .build())
                       .build());
           }
        });
    }

    @Override
    public void handleDeletedAlarm(int alarmId, String reductionKey) {
        if (alarmIdsFiltered.remove(alarmId)) {
            // This alarm was filtered out and not forwarded to PD, do not forward the delete
            return;
        }
        if (dequeueTasks(reductionKey)) {
            return;
        }

        final PDEvent e = new PDEvent();
        e.setEventAction(PDEventAction.RESOLVE);
        e.setDedupKey(reductionKey);
        e.setRoutingKey(serviceConfig.getRoutingKey());
        LOG.info("Sending clear for deleted alarm with reduction-key: {}", reductionKey);
        pdClient.sendEvent(e).whenComplete((v,ex) -> {
            if (ex != null) {
                LOG.warn("Sending event for alarm with reduction-key: {} failed.", reductionKey, ex);
            } else {
                LOG.info("Event sent successfully for alarm with reduction-key: {}", reductionKey);
            }
        });
    }

    @Override
    public void handleAlarmSnapshot(List<Alarm> alarms) {
        // TODO: Add snapshot handling support - we should add some persistent state handling before we do this though
    }

    public PDEvent toEvent(Alarm alarm) {
        final PDEvent e = new PDEvent();
        e.setClient(pluginConfig.getClient());
        e.setClientUrl(String.format(pluginConfig.getAlarmDetailsUrlPattern(), alarm.getId()));
        e.setRoutingKey(serviceConfig.getRoutingKey());
        e.setDedupKey(alarm.getReductionKey());

        if (Severity.CLEARED.equals(alarm.getSeverity()) || AlarmType.RESOLUTION.equals(alarm.getType())) {
            e.setEventAction(PDEventAction.RESOLVE);
        } else if (alarm.isAcknowledged()) {
            e.setEventAction(PDEventAction.ACKNOWLEDGE);
        } else {
            e.setEventAction(PDEventAction.TRIGGER);
        }

        final PDEventPayload payload = new PDEventPayload();
        e.setPayload(payload);
        // Log message -> Summary
        payload.setSummary(alarm.getLogMessage().trim());
        // Severity -> Severity
        payload.setSeverity(PDEventSeverity.fromOnmsSeverity(alarm.getSeverity()));
        // Use the node label as the source if available
        if (alarm.getNode() != null) {
            payload.setSource(alarm.getNode().getLabel());
        } else {
            payload.setSource("unknown");
        }
        if (!Strings.isNullOrEmpty(alarm.getManagedObjectType()) && !Strings.isNullOrEmpty(alarm.getManagedObjectInstance())) {
            // Use the MO type/instance if set
            payload.setComponent(String.format("%s - %s", alarm.getManagedObjectType(), alarm.getManagedObjectInstance()));
        }
        // Add all of the event parameters as custom details
        final DatabaseEvent dbEvent = alarm.getLastEvent();
        if (dbEvent != null) {
            payload.getCustomDetails().putAll(eparmsToMap(dbEvent.getParameters()));
        }

        return e;
    }

    protected static Map<String, Object> eparmsToMap(List<EventParameter> eparms) {
        final Map<String, Object> map = new LinkedHashMap<>();
        if (eparms == null) {
            return map;
        }
        eparms.forEach(p -> map.put(p.getName(), p.getValue()));
        return map;
    }

    @Override
    public void close() {
        try {
            pdClient.close();
        } catch (IOException e) {
            LOG.warn("Error while closing statuspage client. Resources may not be cleaned up properly.", e);
        }
        executor.shutdownNow();
    }

    public static boolean testAlarmAgainstExpression(JexlExpression expression, Alarm alarm) {
        final JexlContext jc = new MapContext();
        jc.set("alarm", alarm);
        return (boolean)expression.evaluate(jc);
    }

    private class TaskConsumer implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    LOG.debug("Waiting for a task to become available...");
                    StatusPageForwarderTask task = taskQueue.take();
                    LOG.debug("Received statuspageForwarderTask: {}", task);
                    sendPDEvent(task.getReductionKey(), task.getPdEvent());
                } catch (InterruptedException e) {
                    LOG.info("TaskConsumer interrupted. Stopping.");
                    break;
                }
            }
        }

    }

}
