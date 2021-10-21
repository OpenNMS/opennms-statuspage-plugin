# OpenNMS Statuspage Plugin 

## Overview

This plugin allows you to create Statuspage incidents that correspond to alarms in OpenNMS by integrating with the [Statuspage API 1.0.0](https://developer.statuspage.io/).

OpenNMS alarms can be used to create incidents in Statuspage:
* Events create incidents
* Events are deduplicated by the `dedup_key` (or `reduction key` in OpenNMS)
* Incidents can be acknowledged while someone is working on the problem
* Incidents are closed when there is no longer a problem