# Machine learning (learns from real use)

Two models that keep learning from what happens on the road. Both save what they learned in the database, so a restart loses nothing, and both report their real accuracy in the Control Centre.

| File | What it is |
|---|---|
| `TrafficSpeedModel.java` | Learns the normal speed of every road, each direction, each hour of the week, from live location updates (Welford running mean / variance, empirical-Bayes shrinkage). Detects jams live, warns drivers heading into them, predicts roads that are usually slow at a time. |
| `TrafficSpeedStat.java` / `TrafficSpeedStatRepository.java` | What it learned - table `traffic_speed_stats`. |
| `ReportTrustModel.java` | Online logistic regression: how likely a new road report is to be true, learned from every report the community trusts or takes down. |
| `MlModelState.java` / `MlModelStateRepository.java` | A model's saved weights and score - table `ml_models`. |
