package com.jianguo.mistep;

import android.graphics.Rect;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 64 request contract for real leashes; Host and WMS acknowledgements remain distinct. */
final class LiveTaskSwapDriver {
    private LiveTaskSwapDriver() {}

    static final class Peer {
        final LiveSwapGeometry.Task task;
        final Object controller;
        Peer(LiveSwapGeometry.Task task, Object controller) {
            if (task == null || controller == null) throw new IllegalArgumentException("peer identity missing");
            this.task = task; this.controller = controller;
        }
    }

    static final class Flip {
        final LiveSwapGeometry.Task first, second;
        final int userId;
        final Rect logical;
        final Object hostRoot;
        final LiveSwapZoom.Zoom oldZoom, targetZoom;
        final LiveSwapZoom.Bridge zoom;
        final LiveSwapZoom.Layout layout;
        final Peer[] peers;
        final Object[] cellBackgrounds;
        final Object backdrop;
        Flip(LiveSwapGeometry.Task first, LiveSwapGeometry.Task second, int userId, Rect logical,
                Object hostRoot, LiveSwapZoom.Zoom oldZoom, LiveSwapZoom.Zoom targetZoom,
                LiveSwapZoom.Bridge zoom, LiveSwapZoom.Layout layout, Peer[] peers,
                Object[] cellBackgrounds) {
            this(first, second, userId, logical, hostRoot, oldZoom, targetZoom, zoom, layout, peers, cellBackgrounds, null);
        }
        Flip(LiveSwapGeometry.Task first, LiveSwapGeometry.Task second, int userId, Rect logical,
                Object hostRoot, LiveSwapZoom.Zoom oldZoom, LiveSwapZoom.Zoom targetZoom,
                LiveSwapZoom.Bridge zoom, LiveSwapZoom.Layout layout, Peer[] peers,
                Object[] cellBackgrounds, Object backdrop) {
            if (first == null || second == null || first.id == second.id || first.token.equals(second.token)
                    || logical == null || logical.isEmpty() || hostRoot == null || oldZoom == null
                    || targetZoom == null || targetZoom.generation <= oldZoom.generation
                    || zoom == null || layout == null || peers == null || cellBackgrounds == null)
                throw new IllegalArgumentException("incomplete live flip request");
            this.first = first; this.second = second; this.userId = userId;
            this.logical = new Rect(logical); this.hostRoot = hostRoot;
            this.oldZoom = oldZoom; this.targetZoom = targetZoom; this.zoom = zoom; this.layout = layout;
            this.peers = peers.clone(); this.cellBackgrounds = cellBackgrounds.clone();
            this.backdrop = backdrop;
        }
    }

    static final class Home {
        final LiveSwapGeometry.Task app;
        final int homeId, userId;
        final Object homeToken, hostRoot;
        final Rect logical;
        final LiveSwapZoom.Zoom zoom;
        final Object[] cellBackgrounds;
        final Object backdrop;
        Home(LiveSwapGeometry.Task app, int homeId, Object homeToken, int userId, Rect logical,
                Object hostRoot, LiveSwapZoom.Zoom zoom, Object[] cellBackgrounds) {
            this(app, homeId, homeToken, userId, logical, hostRoot, zoom, cellBackgrounds, null);
        }
        Home(LiveSwapGeometry.Task app, int homeId, Object homeToken, int userId, Rect logical,
                Object hostRoot, LiveSwapZoom.Zoom zoom, Object[] cellBackgrounds, Object backdrop) {
            if (app == null || homeId < 0 || homeId == app.id || homeToken == null
                    || homeToken.equals(app.token) || logical == null || logical.isEmpty()
                    || hostRoot == null || zoom == null || cellBackgrounds == null)
                throw new IllegalArgumentException("incomplete live Home request");
            this.app = app; this.homeId = homeId; this.homeToken = homeToken; this.userId = userId;
            this.logical = new Rect(logical); this.hostRoot = hostRoot; this.zoom = zoom;
            this.cellBackgrounds = cellBackgrounds.clone();
            this.backdrop = backdrop;
        }
    }

    static boolean startFlip(ClassLoader loader, Object taskViews, Object controller, Flip request,
            BooleanSupplier current, Consumer<LiveSwapGeometry.Frame[]> input,
            Consumer<MiStepLiveSwap.Result> done, Consumer<String> log) {
        return MiStepLiveSwap.startFlip(loader, taskViews, controller, request, current, input, done, log);
    }

    static boolean startHome(ClassLoader loader, Object taskViews, Object controller, Home request,
            BooleanSupplier current, Consumer<LiveSwapGeometry.Frame[]> input,
            Consumer<MiStepLiveSwap.Result> done, Consumer<String> log) {
        return MiStepLiveSwap.startHome(loader, taskViews, controller, request, current, input, done, log);
    }
}
