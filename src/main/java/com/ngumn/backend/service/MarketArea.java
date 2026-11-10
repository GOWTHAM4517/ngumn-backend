package com.ngumn.backend.service;

import com.ngumn.backend.util.GeoUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where Buy &amp; Sell works: one city and the towns people travel in from
 * every day - by default Vijayawada and about 40 km around it (Gannavaram,
 * Amaravati, Mangalagiri, Guntur, Tenali). Vehicles for sale, test drives and
 * "for sale near you" all stay inside it, so every buyer can reach every
 * seller. Set with ngumn.market.* (MARKET_AREA_LAT / _LNG / _RADIUS_KM / _NAME).
 */
@Component
public class MarketArea {

    @Value("${ngumn.market.area-name:Vijayawada area}")
    private String name = "Vijayawada area";

    @Value("${ngumn.market.area-places:Vijayawada, Gannavaram, Amaravati, Mangalagiri, Guntur, Tenali}")
    private String places = "Vijayawada, Gannavaram, Amaravati, Mangalagiri, Guntur, Tenali";

    @Value("${ngumn.market.area-lat:16.5062}")
    private double latitude = 16.5062;

    @Value("${ngumn.market.area-lng:80.6480}")
    private double longitude = 80.6480;

    @Value("${ngumn.market.area-radius-km:40}")
    private double radiusKm = 40;

    public boolean contains(double lat, double lng) {
        return GeoUtil.distanceMeters(latitude, longitude, lat, lng) <= radiusKm * 1000;
    }

    /** How far outside the area a place is, in km (0 inside). */
    public double outsideKm(double lat, double lng) {
        return Math.max(0, GeoUtil.distanceMeters(latitude, longitude, lat, lng) / 1000 - radiusKm);
    }

    public String name() {
        return name;
    }

    public double radiusKm() {
        return radiusKm;
    }

    /** "Buy & Sell covers the Vijayawada area (Vijayawada, Gannavaram, ...) - this place is 12 km outside it." */
    public String outsideMessage(double lat, double lng) {
        return "Buy & Sell covers the " + name + " (" + places + "), so buyers and sellers can reach each other - this place is "
                + Math.round(outsideKm(lat, lng)) + " km outside it.";
    }

    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("places", List.of(places.split("\\s*,\\s*")));
        m.put("latitude", latitude);
        m.put("longitude", longitude);
        m.put("radiusKm", radiusKm);
        return m;
    }
}
