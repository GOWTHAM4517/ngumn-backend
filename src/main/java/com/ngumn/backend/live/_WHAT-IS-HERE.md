# Live data from outside

| File | What it is |
|---|---|
| `WeatherService.java` | Live weather for Vijayawada, Guntur, Gannavaram, Amaravati, Mangalagiri and Tenali from Open-Meteo every 15 minutes; pushes thunderstorm / heavy rain / fog / heat / wind warnings to people in that town. |
| `LiveDataStatus.java` / `LiveDataStatusRepository.java` | When each live source (roads, weather) last worked, and what it brought - table `live_data_status`. |
| `LegacyDemoCleanup.java` | Removes the old Demo Mode data (Hyderabad sample zones, simulated vehicles, sample reports) from databases made before it was taken out. |
