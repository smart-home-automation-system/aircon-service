# aircon-service

The air conditioners of the house (epic HAS-376). The scaffold of HAS-380, from the scaffold of
energy-service (itself from water-service): Spring Boot 4.1.2, cholewa-commons 1.13.0, smart-home-sdk
1.18.0, shelly-client 2.6.0 with its beans on; port 6015 / 8015 locally, 6200 / 8200 in the cluster; own
database `home-automation-aircon`, pool 2, `V1` an empty baseline; `database-password` mandatory;
scheduling off under `test`. Org-wide rules: `organization-repository/claude/organization.md`. The
registry, the Gree driver and the first endpoint come with the rest of HAS-380.
