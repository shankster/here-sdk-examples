/*
 * Copyright (C) 2019-2020 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.navigation;

import android.content.Context;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;

import com.here.sdk.core.Color;
import com.here.sdk.core.GeoCoordinates;
import com.here.sdk.core.GeoPolyline;
import com.here.sdk.core.errors.InstantiationErrorException;
import com.here.sdk.gestures.GestureState;
import com.here.sdk.mapview.MapImage;
import com.here.sdk.mapview.MapImageFactory;
import com.here.sdk.mapview.MapMarker;
import com.here.sdk.mapview.MapPolyline;
import com.here.sdk.mapview.MapView;
import com.here.sdk.routing.CarOptions;
import com.here.sdk.routing.Route;
import com.here.sdk.routing.RoutingEngine;
import com.here.sdk.routing.Waypoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// Allows to calculate a route and start navigation, using either platform positioning or
// simulated locations.
public class RoutingExample {

    public static final GeoCoordinates DEFAULT_MAP_CENTER = new GeoCoordinates(52.520798, 13.409408);
    public static final int DEFAULT_DISTANCE_IN_METERS = 1000 * 2;

    private final Context context;
    private final MapView mapView;
    private final List<MapMarker> mapMarkerList = new ArrayList<>();
    private final List<MapPolyline> mapPolylines = new ArrayList<>();
    private RoutingEngine routingEngine;
    private GeoCoordinates startGeoCoordinates;
    private GeoCoordinates destinationGeoCoordinates;
    private boolean isLongpressDestination;
    private final NavigationExample navigationExample;

    public RoutingExample(Context context, MapView mapView) {
        this.context = context;
        this.mapView = mapView;
        this.mapView.getCamera().lookAt(DEFAULT_MAP_CENTER, DEFAULT_DISTANCE_IN_METERS);

        try {
            routingEngine = new RoutingEngine();
        } catch (InstantiationErrorException e) {
            throw new RuntimeException("Initialization of RoutingEngine failed: " + e.error.name());
        }

        navigationExample = new NavigationExample(context, mapView);
        navigationExample.startTracking();

        setLongPressGestureHandler();
        Snackbar.make(mapView, "Long press to set a destination or use a random one.", Snackbar.LENGTH_LONG).show();
    }

    // Calculate a route and start navigation using a location simulator.
    // Start is map center and destination location is set random within viewport,
    // unless a destination is set via long press.
    public void addRouteSimulatedLocation() {
        calculateRoute(true);
    }

    // Calculate a route and start navigation using locations from device.
    // Start is current location and destination is set random within viewport,
    // unless a destination is set via long press.
    public void addRouteDeviceLocation() {
        calculateRoute(false);
    }

    public void clearMapButtonPressed() {
        clearMap();
        isLongpressDestination = false;
    }

    private void calculateRoute(boolean isSimulated) {
        clearMap();

        if (!determineRouteWaypoints(isSimulated)) {
            return;
        }

        Waypoint startWaypoint = new Waypoint(startGeoCoordinates);
        Waypoint destinationWaypoint = new Waypoint(destinationGeoCoordinates);

        List<Waypoint> waypoints =
                new ArrayList<>(Arrays.asList(startWaypoint, destinationWaypoint));

        routingEngine.calculateRoute(
                waypoints,
                new CarOptions(),
                (routingError, routes) -> {
                    if (routingError == null) {
                        Route route = routes.get(0);
                        showRouteOnMap(route);
                        showRouteDetails(route, isSimulated);
                    } else {
                        showDialog("Error while calculating a route:", routingError.toString());
                    }
                });
    }

    private boolean determineRouteWaypoints(boolean isSimulated) {
        if (!isSimulated && navigationExample.getLastKnownGeoCoordinates() == null) {
            showDialog("Error", "No location found.");
            return false;
        }

        if (isSimulated) {
            startGeoCoordinates = getMapViewCenter();
        } else {
            startGeoCoordinates = navigationExample.getLastKnownGeoCoordinates();
            mapView.getCamera().lookAt(startGeoCoordinates);
        }

        if (!isLongpressDestination) {
            destinationGeoCoordinates = createRandomGeoCoordinatesAroundMapCenter();
        }

        // Add circles to indicate start and destination of route.
        addCircleMapMarker(startGeoCoordinates, R.drawable.green_dot);
        addCircleMapMarker(destinationGeoCoordinates, R.drawable.green_dot);

        return true;
    }

    private void showRouteDetails(Route route, boolean isSimulated) {
        long estimatedTravelTimeInSeconds = route.getDurationInSeconds();
        int lengthInMeters = route.getLengthInMeters();

        String routeDetails =
                "Travel Time: " + formatTime(estimatedTravelTimeInSeconds)
                        + ", Length: " + formatLength(lengthInMeters);

        showStartNavigationDialog("Route Details", routeDetails, route, isSimulated);
    }

    private String formatTime(long sec) {
        int hours = (int) (sec / 3600);
        int minutes = (int) ((sec % 3600) / 60);

        return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes);
    }

    private String formatLength(int meters) {
        int kilometers = meters / 1000;
        int remainingMeters = meters % 1000;

        return String.format(Locale.getDefault(), "%02d.%02d km", kilometers, remainingMeters);
    }

    private void showRouteOnMap(Route route) {
        // Show route as polyline.
        GeoPolyline routeGeoPolyline;
        try {
            routeGeoPolyline = new GeoPolyline(route.getPolyline());
        } catch (InstantiationErrorException e) {
            // It should never happen that the route polyline contains less than two vertices.
            return;
        }

        float widthInPixels = 20;
        MapPolyline routeMapPolyline = new MapPolyline(routeGeoPolyline,
                widthInPixels,
                new Color((short) 0x00, (short) 0x90, (short) 0x8A, (short) 0xA0));

        mapView.getMapScene().addMapPolyline(routeMapPolyline);
        mapPolylines.add(routeMapPolyline);
    }

    public void clearMap() {
        clearWaypointMapMarker();
        clearRoute();

        navigationExample.stopNavigation();
        navigationExample.startTracking();
    }

    private void clearWaypointMapMarker() {
        for (MapMarker mapMarker : mapMarkerList) {
            mapView.getMapScene().removeMapMarker(mapMarker);
        }
        mapMarkerList.clear();
    }

    private void clearRoute() {
        for (MapPolyline mapPolyline : mapPolylines) {
            mapView.getMapScene().removeMapPolyline(mapPolyline);
        }
        mapPolylines.clear();
    }

    private void setLongPressGestureHandler() {
        mapView.getGestures().setLongPressListener((gestureState, touchPoint) -> {
            GeoCoordinates geoCoordinates = mapView.viewToGeoCoordinates(touchPoint);
            if (geoCoordinates == null) {
                return;
            }
            if (gestureState == GestureState.BEGIN) {
                clearWaypointMapMarker();
                clearRoute();
                destinationGeoCoordinates = geoCoordinates;
                addCircleMapMarker(destinationGeoCoordinates, R.drawable.green_dot);
                isLongpressDestination = true;
                Snackbar.make(mapView, "New long press destination set.", Snackbar.LENGTH_SHORT).show();
            }
        });
    }

    private GeoCoordinates createRandomGeoCoordinatesAroundMapCenter() {
        GeoCoordinates centerGeoCoordinates = getMapViewCenter();
        double lat = centerGeoCoordinates.latitude;
        double lon = centerGeoCoordinates.longitude;
        return new GeoCoordinates(getRandom(lat - 0.02, lat + 0.02),
                getRandom(lon - 0.02, lon + 0.02));
    }

    private double getRandom(double min, double max) {
        return min + Math.random() * (max - min);
    }

    private GeoCoordinates getMapViewCenter() {
        return mapView.getCamera().getState().targetCoordinates;
    }

    private void addCircleMapMarker(GeoCoordinates geoCoordinates, int resourceId) {
        MapImage mapImage = MapImageFactory.fromResource(context.getResources(), resourceId);
        MapMarker mapMarker = new MapMarker(geoCoordinates, mapImage);

        mapView.getMapScene().addMapMarker(mapMarker);
        mapMarkerList.add(mapMarker);
    }

    private void showDialog(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
               .setMessage(message)
               .show();
    }

    private void showStartNavigationDialog(String title, String message, Route route, boolean isSimulated) {
        String buttonText = isSimulated ? "Start navigation (simulated)" : "Start navigation (device location)";
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title)
               .setMessage(message)
               .setNeutralButton(buttonText,
                       (dialog, which) -> {
                           navigationExample.stopTracking();
                           navigationExample.startNavigation(route, isSimulated);
                       })
               .show();
    }
}
