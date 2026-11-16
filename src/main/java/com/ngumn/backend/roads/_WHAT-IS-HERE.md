# Real roads (OpenStreetMap) and the speed-limit law

Every speed limit and one-way road Raksio checks comes from here - the real road map of the Vijayawada region, not values typed in.

| File | What it is |
|---|---|
| `RoadDataService.java` | Downloads the region's roads from OpenStreetMap (Overpass API) in 16 tiles, saves them, refreshes every Sunday, retries hourly after a failure. |
| `OsmRoadParser.java` | Reads the Overpass answer: road class, name, number (NH16), posted limit, one-way direction, divided carriageway, lanes, shape. |
| `RoadSegment.java` / `RoadSegmentRepository.java` | One road (one OSM way) in the database - table `road_segments`. |
| `RoadNetwork.java` | The roads in memory with a grid index: "which road is this GPS point on?" in microseconds. |
| `RoadRules.java` | The road under a GPS point and its rules: picks the right carriageway by heading, decides wrong-way carefully. |
| `SpeedLimitLaw.java` | India's legal limits (MoRTH S.O. 1522(E), 6 Apr 2018) by vehicle and road type, the 5% tolerance, reading OSM maxspeed values. |
| `RoadInfo.java` | What the app gets back with every location update: road name, its limit and where it comes from, one-way, wrong-way. |
| `Polyline.java` | Google's encoded polyline - a road's shape as one short string. |
