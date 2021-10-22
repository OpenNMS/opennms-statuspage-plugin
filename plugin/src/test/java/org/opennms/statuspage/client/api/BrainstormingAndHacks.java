package org.opennms.statuspage.client.api;

import ws.slink.statuspage.StatusPage;
import ws.slink.statuspage.model.Component;
import ws.slink.statuspage.model.Incident;
import ws.slink.statuspage.model.Page;
import ws.slink.statuspage.type.ComponentStatus;
import ws.slink.statuspage.type.IncidentSeverity;
import ws.slink.statuspage.type.IncidentStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class BrainstormingAndHacks {

    // TODO: fill me in
    private static final String API_KEY = "";

//    @Test
    public void testGetPage() {
        var statusPage = statusPage();
        var pages = statusPage.pages();
        for (Page page: pages) {
            System.out.println("Page:");
            System.out.println("\tId: " + page.id());
            System.out.println("\tName: " + page.name());

            // Note: the page fetch is shallow - this won't be populated.
//            System.out.println("\tComponent: ");
//
//            for (Component component : page.components()) {
//                System.out.println("\t\tId:" + component.id());
//                System.out.println("\t\tName:" + component.name());
//            }
        }

//        System.out.println(
//                "Components? \n\n" + statusPage.getComponent("", ""));
    }

//    @Test
    public void testRaiseIncident() {
        var statusPage = statusPage();

        var pageId = ""; // TODO: fill me in
        var componentId = ""; // TODO: fill me in
        var groupId = ""; // TODO: fill me in

        List<Component> components = new ArrayList<>();
        components.add(new Component().id(componentId).groupId(groupId).status(ComponentStatus.MAINTENANCE));

        var incident = new Incident()
                .name("Test")
                .status(IncidentStatus.INVESTIGATING)
                .impact(IncidentSeverity.MINOR)
                .createdAt(LocalDateTime.now())
                .pageId(pageId)
                .components(components);

        var body = "This incident is a test, kindly disregard.";

        var raisedIncident = statusPage.createIncident(pageId, incident, body).orElseThrow();
        System.out.println(raisedIncident);
    }

//    @Test
    public void testResolveIncident() {
        var statusPage = statusPage();

        var pageId = ""; // TODO: fill me in
        var incidentId = ""; // TODO: this should be retrieved from ONMS (save in the event?).

        var currentIncident = statusPage
                .getIncident(pageId, incidentId)
                .orElseThrow();

        System.out.println(currentIncident);

        // TODO: this should be the time that the event was resolved from ONMS' perspective.
        var updatedIncident = currentIncident
                .resolvedAt(LocalDateTime.now())
                .status(IncidentStatus.RESOLVED)
                .pageId(pageId);

        // TODO: need to update the component status as well!

        var body = "End of test";

        var resultingIncident = statusPage.updateIncident(pageId, updatedIncident, body).orElseThrow();
        System.out.println(resultingIncident);
    }

    private static StatusPage statusPage() {
        return new StatusPage.Builder()
                .apiKey(API_KEY)
                .bridgeErrors(true)
                .rateLimit(true)
                .rateLimitDelay(1000)
                .build();
    }

    // Notes:
    // 1. in the Karaf config:
    //  - API Key
    //  - Page Id
    //  - Component Id
    // 2. If the event involves a node or service, we can toggle the type of the outage (e.g. degraded for service -
    //    can also consider service down)
    // Future:
    // - scheduled outage syncs to status page
    // - verify license for 3rd party dependency:
    //     https://search.maven.org/artifact/ws.slink/atlassian-status-page-java-api/0.0.1/jar
}
