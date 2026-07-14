package app.morphe.extension.music.patches.pinplaylist;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.SparseArray;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings({"unused", "rawtypes", "unchecked"})
public final class PinPlaylistPatch {
    private static final String TAG = "PinPlaylist";
    private static final String BUILD_ID = "v92-safe-flyout-hooks";
    private static final String[] MENU_ITEM_HELPER_CLASSES =
            {"arbe", "aqft"};
    private static final String[] ICON_ENUM_CLASSES =
            {"btcw", "brfz"};
    private static final String[] TEXT_HELPER_CLASSES =
            {"bcow", "bbjy"};
    private static final String[] LIBRARY_ADAPTER_CLASSES =
            {"hyz", "hvx"};
    private static final String MENU_TITLE_PIN =
            "Pin playlist to Library";
    private static final String MENU_TITLE_UNPIN =
            "Unpin playlist from Library";
    private static final String TOAST_PINNED =
            "Pinned to Library";
    private static final String TOAST_UNPINNED =
            "Unpinned from Library";
    private static final int MAX_TRACKED_MENU_ITEMS = 256;

    /*
     * Reflection limits prevent a malformed or unexpectedly large renderer graph
     * from causing an expensive traversal on the UI thread.
     */
    private static final int MAX_REFLECTION_DEPTH = 10;
    private static final int MAX_VISITED_OBJECTS_PER_ROW = 1200;
    private static final int MAX_DIAGNOSTIC_STRINGS = 40;
    private static final int MAX_FLYOUT_OBJECT_MAPPINGS = 4000;

    /*
     * Real user-created YouTube playlist IDs are substantially longer than
     * renderer/page tokens such as PLAYLISTS_PAGE and PLAYLISTS_PAGEB.
     * Requiring a realistic PL payload prevents those page constants from
     * being mistaken for the identity of every bound Library row.
     */
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile(
            "(OLAK5uy_[A-Za-z0-9_-]{8,}|PL[A-Za-z0-9_-]{30,}|LRSR[A-Za-z0-9_-]{8,})"
    );

    private static final IdentityHashMap<Object, String> flyoutObjectIds =
            new IdentityHashMap<>();

    private static final IdentityHashMap<Object, String> flyoutPresenterIds =
            new IdentityHashMap<>();

    private static final IdentityHashMap<Object, Boolean>
            injectedLibraryPinMenuItems = new IdentityHashMap<>();

    private static final long ACTIVE_FLYOUT_ID_TTL_MS = 30_000L;
    private static final long PENDING_FLYOUT_VIEW_ID_TTL_MS = 2_000L;

    @Nullable
    private static volatile String activeFlyoutPlaylistId;

    private static volatile long activeFlyoutCapturedAtMs;

    @Nullable
    private static volatile String pendingFlyoutViewPlaylistId;

    private static volatile long pendingFlyoutViewCapturedAtMs;

    private static boolean flyoutSourceEntryLogged;
    private static boolean flyoutViewEntryLogged;

    @Nullable
    private static volatile Long activeFlyoutStableRowId;

    @Nullable
    private static volatile View activeFlyoutRowView;

    @Nullable
    private static volatile String activeFlyoutRowPlaylistId;

    private static volatile int activeFlyoutAdapterPosition = -1;

    @Nullable
    private static volatile Object activeLibraryAdapter;

    @Nullable
    private static volatile List<?> activeLibraryBackingList;

    private static final IdentityHashMap<Object, Map<Long, String>>
            adapterPlaylistIds = new IdentityHashMap<>();

    private static final IdentityHashMap<Object, List<Long>>
            adapterBaseOrder = new IdentityHashMap<>();

    private static final IdentityHashMap<Object, List<Long>>
            adapterLastAppliedOrder = new IdentityHashMap<>();

    private static final IdentityHashMap<Object, Boolean>
            adapterReorderPending = new IdentityHashMap<>();

    private static final IdentityHashMap<Object, Integer>
            adapterBindReorderGeneration = new IdentityHashMap<>();

    private static final IdentityHashMap<Object, Integer>
            adapterExpectedPlaylistCount = new IdentityHashMap<>();

    /*
     * Once one Library list has been identified after binding, this gives the
     * pre-submit path an exact safety threshold for later sort/refresh lists.
     */
    private static volatile int lastKnownLibraryPlaylistCount;

    /*
     * Pre-submit AdapterProxy bridge.
     *
     * bfrh.h(index) creates each Lhyi render-info object. A later bfrh method
     * builds the complete List<Lhzq>, clears hzc.b, converts every Lhzq to hvg,
     * and only then notifies hyz. Capturing owner/index/renderInfo at h() return
     * lets us partition that source list before the first adapter render.
     */
    private static boolean adapterProxyHookLogged;
    private static int adapterProxyAttemptLogCount;
    private static int directSourceRowLogCount;

    private static final IdentityHashMap<Object, AdapterProxySource>
            adapterProxySources = new IdentityHashMap<>();

    /*
     * Some bfrh lists reuse the same render-info object in multiple positions.
     * An IdentityHashMap then collapses every position to the final h(index)
     * invocation. Preserve each completed invocation in order as well.
     */
    private static final IdentityHashMap<Object, ArrayList<AdapterProxySource>>
            adapterProxySourceHistory = new IdentityHashMap<>();

    /*
     * AdapterProxyRenderInfo is one shared singleton for all delegated rows.
     * Reordering List<Lhzq> therefore cannot change what the proxy adapter
     * binds. Present a virtual reordered adapter instead: hyz keeps its native
     * backing list, while its position-based methods translate visual position
     * to the corresponding native bewt source position.
     */
    private static final IdentityHashMap<Object, int[]>
            adapterVisualToSourcePositions =
            new IdentityHashMap<>();

    /*
     * Visible local-pin state for the Library rows. The factory map already
     * knows the canonical playlist ID assigned to every final visual slot, so
     * retain that metadata beside the position permutation and decorate only
     * rows that are locally pinned.
     */
    private static final String LEGACY_ROW_PIN_PREFIX = "\uD83D\uDCCC ";

    private static final IdentityHashMap<Object, Map<Integer, String>>
            ownerVisualPlaylistIds =
            new IdentityHashMap<>();

    private static final IdentityHashMap<Object, Set<Integer>>
            ownerPinnedVisualPositions =
            new IdentityHashMap<>();

    private static final IdentityHashMap<Object, Map<Integer, String>>
            adapterVisualPlaylistIds =
            new IdentityHashMap<>();

    private static final IdentityHashMap<Object, Set<Integer>>
            adapterPinnedVisualPositions =
            new IdentityHashMap<>();

    private static final IdentityHashMap<TextView, Boolean>
            activePinIndicatorTextViews =
            new IdentityHashMap<>();

    private static final IdentityHashMap<TextView, Drawable>
            originalSubtitleStartDrawables =
            new IdentityHashMap<>();

    private static final IdentityHashMap<TextView, Integer>
            originalSubtitleDrawablePaddings =
            new IdentityHashMap<>();

    private static final IdentityHashMap<Object, Map<Integer, View>>
            adapterVisibleRowViews =
            new IdentityHashMap<>();

    private static int rowPinIndicatorLogCount;
    private static int nativeToggleGuardLogCount;

    private static final ThreadLocal<Object>
            pendingAdapterPositionRemapTarget =
            new ThreadLocal<>();

    private static final ThreadLocal<String>
            pendingAdapterPositionRemapKind =
            new ThreadLocal<>();

    /*
     * Build the pinned permutation before bfrh.h(index) reads its source item.
     * This is earlier than both RecyclerView binding and the completed
     * pre-submit list, so the first Library frame can contain the final pinned
     * order without hiding any rows.
     */
    private static final IdentityHashMap<Object, int[]>
            adapterProxyFactoryVisualToSource =
            new IdentityHashMap<>();

    private static final IdentityHashMap<Object, Integer>
            adapterProxyFactorySourceCounts =
            new IdentityHashMap<>();

    private static int adapterProxyFactoryMapLogCount;
    private static int adapterProxyFactoryInstallLogCount;

    private static Object activeAdapterProxyFactoryOwner;
    private static Object activeAdapterProxyFactoryVisualAdapter;

    /*
     * A clean install has no local pins. In that state, all Library identity
     * discovery, source-history capture, and adapter-reorder work is useless.
     * Cache the answer once per process and make those hot hooks near-no-ops.
     */
    private static volatile Boolean processHasAnyPins;
    private static boolean noPinColdStartBypassLogged;
    private static int noPinFactoryContextLogCount;

    private static final Object featureStateLock =
            new Object();

    @Nullable
    private static volatile Boolean lastFeatureEnabledState;

    @Nullable
    private static volatile Boolean lastSeparateMenuItemEnabledState;

    private static volatile boolean featureStoreStateSynchronized;

    /*
     * bfrh.h(index) can re-enter while constructing a row. A single ThreadLocal
     * slot is therefore unsafe: an inner call overwrites the outer call's
     * source index/object. Keep one frame per invocation instead.
     */
    private static final ThreadLocal<ArrayList<AdapterProxySource>>
            pendingAdapterProxySources =
            new ThreadLocal<ArrayList<AdapterProxySource>>() {
                @Override
                protected ArrayList<AdapterProxySource>
                initialValue() {
                    return new ArrayList<>();
                }
            };

    private static final ThreadLocal<Object>
            pendingAdapterProxyOwner = new ThreadLocal<>();

    private static final class AdapterProxySource {
        final Object owner;
        final int sourceIndex;
        Object sourceObject;
        Object renderInfo;
        long completedAtMs;

        AdapterProxySource(Object owner, int sourceIndex) {
            this.owner = owner;
            this.sourceIndex = sourceIndex;
        }
    }


    private static final class AdapterProxyMapping {
        final String path;
        final int offset;
        final int score;
        final Map<Integer, String> idsByListPosition;
        final boolean ambiguous;

        AdapterProxyMapping(
                String path,
                int offset,
                int score,
                Map<Integer, String> idsByListPosition,
                boolean ambiguous
        ) {
            this.path = path;
            this.offset = offset;
            this.score = score;
            this.idsByListPosition = idsByListPosition;
            this.ambiguous = ambiguous;
        }
    }


    /*
     * Entry/return bridge for hyz.o. Capturing the row only after the binder
     * returns removes the old View.post delay while preserving the original
     * adapter, holder, position, and stable row ID.
     */
    private static final IdentityHashMap<Object, PendingBoundRow>
            pendingBoundRows = new IdentityHashMap<>();

    private static final class PendingBoundRow {
        final Object adapter;
        final Object holder;
        final int position;
        final long stableId;
        final View itemView;

        PendingBoundRow(
                Object adapter,
                Object holder,
                int position,
                long stableId,
                View itemView
        ) {
            this.adapter = adapter;
            this.holder = holder;
            this.position = position;
            this.stableId = stableId;
            this.itemView = itemView;
        }
    }

    /*
     * A full adapter notification rebinds existing holders. Their posted
     * identity callbacks can briefly observe a holder after it has been reused
     * for another row, which would overwrite correct stable-ID mappings.
     */
    private static final IdentityHashMap<Object, Long>
            adapterBindSuppressedUntilMs = new IdentityHashMap<>();

    private static final IdentityHashMap<Object, Integer>
            adapterSuppressionReconcileGeneration = new IdentityHashMap<>();

    private static final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    /*
     * The byhm reference exposed by recycled row Views can become stale after
     * changing Library sort order. Preserve a verified row-text signature to
     * playlist-ID bridge so newly generated stable IDs can be reconstructed
     * without trusting that stale listener reference.
     */
    private static final Map<String, String>
            playlistIdByRowSignature = new LinkedHashMap<>();

    private static final Map<String, String>
            rowSignatureByPlaylistId = new LinkedHashMap<>();

    private static int rowIdentityConflictLogCount;
    private static int stableRowRemapConflictLogCount;
    private static int boundRowLogCount;
    private static int boundRowNoIdLogCount;
    private static boolean deferredBindHookLogged;

    @Nullable
    private static volatile Context applicationContext;

    /**
     * Diagnostic hook for the flyout-opening path. The source object is the
     * renderer/model associated with the menu before the individual bwyn click
     * loses that context.
     */
    private static void resetFlyoutIdentityMappings() {
        activeFlyoutPlaylistId = null;
        activeFlyoutCapturedAtMs = 0L;

        synchronized (flyoutObjectIds) {
            flyoutObjectIds.clear();
        }

        synchronized (flyoutPresenterIds) {
            flyoutPresenterIds.clear();
        }
    }

    private static void clearActiveFlyoutRowContext() {
        activeFlyoutAdapterPosition = -1;
        activeFlyoutStableRowId = null;
        activeFlyoutRowView = null;
        activeFlyoutRowPlaylistId = null;
    }

    private static void rememberPendingFlyoutViewPlaylistId(
            String playlistId
    ) {
        pendingFlyoutViewPlaylistId = playlistId;
        pendingFlyoutViewCapturedAtMs =
                android.os.SystemClock.elapsedRealtime();
    }

    @Nullable
    private static String consumePendingFlyoutViewPlaylistId() {
        String playlistId = pendingFlyoutViewPlaylistId;
        long capturedAt = pendingFlyoutViewCapturedAtMs;

        pendingFlyoutViewPlaylistId = null;
        pendingFlyoutViewCapturedAtMs = 0L;

        if (!PinStore.isSupportedPlaylistId(playlistId)
                || capturedAt <= 0L) {
            return null;
        }

        long ageMs = android.os.SystemClock.elapsedRealtime()
                - capturedAt;

        if (ageMs > PENDING_FLYOUT_VIEW_ID_TTL_MS) {
            Log.d(TAG, "Discarded stale flyout-view playlist id="
                    + playlistId
                    + " ageMs=" + ageMs);
            return null;
        }

        return playlistId;
    }

    @Nullable
    private static String resolveNativeSpeedDialPlaylistId(
            @Nullable Object flyoutMenu
    ) {
        Object listObject =
                readFieldByName(flyoutMenu, "c");

        if (!(listObject instanceof List)) {
            return null;
        }

        for (Object menuItem : (List<?>) listObject) {
            if (!isSpeedDialMenuItem(menuItem)) {
                continue;
            }

            Set<String> ids =
                    collectCanonicalPlaylistIds(menuItem, 14);
            String playlistId =
                    findOnlySupportedPlaylistId(ids);

            if (PinStore.isSupportedPlaylistId(playlistId)) {
                Log.d(TAG, "FlyoutPageIdentityBridge"
                        + " source=nativeSpeedDialCommand"
                        + " playlistId=" + playlistId
                        + " menuItemType="
                        + objectTypeName(menuItem));
                return playlistId;
            }

            Log.d(TAG, "FlyoutPageIdentityBridge"
                    + " source=nativeSpeedDialCommand"
                    + " resolved=false"
                    + " candidateIds=" + ids
                    + " menuItemType="
                    + objectTypeName(menuItem));
        }

        return null;
    }

    @Nullable
    private static String resolvePageMenuGraphPlaylistId(
            @Nullable Object flyoutMenu,
            @Nullable Object flyoutPresenter
    ) {
        Set<String> presenterIds =
                collectCanonicalPlaylistIds(flyoutPresenter, 14);
        String playlistId =
                findOnlySupportedPlaylistId(presenterIds);

        if (PinStore.isSupportedPlaylistId(playlistId)) {
            Log.d(TAG, "FlyoutPageIdentityBridge"
                    + " source=presenterGraph"
                    + " playlistId=" + playlistId
                    + " presenterType="
                    + objectTypeName(flyoutPresenter));
            return playlistId;
        }

        Object listObject =
                readFieldByName(flyoutMenu, "c");
        Set<String> menuIds = new LinkedHashSet<>();
        Map<String, Integer> menuIdCounts =
                new LinkedHashMap<>();

        if (listObject instanceof List) {
            int index = 0;

            for (Object menuItem : (List<?>) listObject) {
                Set<String> itemIds =
                        collectCanonicalPlaylistIds(menuItem, 14);

                if (!itemIds.isEmpty()) {
                    Object title =
                            invokeStaticByNames(
                                    MENU_ITEM_HELPER_CLASSES,
                                    "e",
                                    menuItem
                            );

                    Log.d(TAG, "FlyoutPageMenuCandidate"
                            + " index=" + index
                            + " title=" + title
                            + " ids=" + itemIds
                            + " itemType="
                            + objectTypeName(menuItem));

                    menuIds.addAll(itemIds);

                    for (String itemId : itemIds) {
                        if (!PinStore.isSupportedPlaylistId(itemId)) {
                            continue;
                        }

                        Integer previous =
                                menuIdCounts.get(itemId);
                        menuIdCounts.put(
                                itemId,
                                previous == null ? 1 : previous + 1
                        );
                    }
                }

                index++;
            }
        }

        playlistId =
                findOnlySupportedPlaylistId(menuIds);

        if (!PinStore.isSupportedPlaylistId(playlistId)) {
            playlistId =
                    findConsensusSupportedPlaylistId(menuIdCounts);
        }

        if (PinStore.isSupportedPlaylistId(playlistId)) {
            Log.d(TAG, "FlyoutPageIdentityBridge"
                    + " source=menuGraphConsensus"
                    + " playlistId=" + playlistId
                    + " counts=" + menuIdCounts
                    + " menuType="
                    + objectTypeName(flyoutMenu));
            return playlistId;
        }

        Log.d(TAG, "FlyoutPageIdentityBridge"
                + " source=pageMenuGraphs"
                + " resolved=false"
                + " presenterIds=" + presenterIds
                + " menuIds=" + menuIds
                + " menuIdCounts=" + menuIdCounts
                + " presenterType="
                + objectTypeName(flyoutPresenter)
                + " menuType=" + objectTypeName(flyoutMenu));

        return null;
    }

    @Nullable
    private static String findConsensusSupportedPlaylistId(
            Map<String, Integer> counts
    ) {
        String bestId = null;
        int bestCount = 0;
        int secondBestCount = 0;
        boolean tied = false;

        if (counts == null) return null;

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String candidate = entry.getKey();
            Integer countObject = entry.getValue();

            if (!PinStore.isSupportedPlaylistId(candidate)
                    || countObject == null) {
                continue;
            }

            int count = countObject;

            if (count > bestCount) {
                secondBestCount = bestCount;
                bestCount = count;
                bestId = candidate;
                tied = false;
            } else if (count == bestCount) {
                tied = true;
            } else if (count > secondBestCount) {
                secondBestCount = count;
            }
        }

        if (bestCount < 2
                || tied
                || bestCount <= secondBestCount) {
            Log.d(TAG, "FlyoutPageIdentityConsensus"
                    + " accepted=false"
                    + " counts=" + counts
                    + " bestCount=" + bestCount
                    + " secondBestCount=" + secondBestCount
                    + " tied=" + tied);
            return null;
        }

        Log.d(TAG, "FlyoutPageIdentityConsensus"
                + " accepted=true"
                + " playlistId=" + bestId
                + " bestCount=" + bestCount
                + " secondBestCount=" + secondBestCount
                + " counts=" + counts);

        return bestId;
    }

    public static void captureFlyoutSource(
            @Nullable Object flyoutMenu,
            @Nullable Object sourceObject,
            @Nullable Object flyoutPresenter
    ) {
        try {
            captureFlyoutSourceInternal(
                    flyoutMenu,
                    sourceObject,
                    flyoutPresenter
            );
        } catch (Throwable error) {
            Log.e(TAG, "Failed capturing flyout source", error);
            resetFlyoutIdentityMappings();
        }
    }

    private static void captureFlyoutSourceInternal(
            @Nullable Object flyoutMenu,
            @Nullable Object sourceObject,
            @Nullable Object flyoutPresenter
    ) {
        if (!isFeatureEnabled()) {
            return;
        }

        /*
         * A flyout is modal, so mappings from the previous one must never be
         * reused. This is especially important for page-level menus whose
         * source object does not expose a canonical playlist ID.
         */
        resetFlyoutIdentityMappings();

        if (!flyoutSourceEntryLogged) {
            flyoutSourceEntryLogged = true;
            Log.d(TAG, "DiagnosticBuild=" + BUILD_ID
                    + " flyoutSourceHook=true"
                    + " sourceType=" + objectTypeName(sourceObject)
                    + " menuType=" + objectTypeName(flyoutMenu)
                    + " presenterType=" + objectTypeName(flyoutPresenter));
        }

        Set<String> sourceStrings =
                collectObjectStrings(sourceObject);
        String playlistId =
                findBestPlaylistId(sourceStrings);
        String viewPlaylistId =
                consumePendingFlyoutViewPlaylistId();
        String speedDialPlaylistId = null;
        String menuGraphPlaylistId = null;

        if (!PinStore.isSupportedPlaylistId(playlistId)
                && PinStore.isSupportedPlaylistId(viewPlaylistId)) {
            playlistId = viewPlaylistId;

            Log.d(TAG, "FlyoutPageIdentityBridge"
                    + " resolved=true"
                    + " playlistId=" + playlistId
                    + " sourceType=" + objectTypeName(sourceObject));
        } else if (PinStore.isSupportedPlaylistId(playlistId)
                && PinStore.isSupportedPlaylistId(viewPlaylistId)
                && !playlistId.equals(viewPlaylistId)) {
            Log.d(TAG, "FlyoutPageIdentityBridge"
                    + " ignoredMismatchedViewId=" + viewPlaylistId
                    + " sourceId=" + playlistId
                    + " sourceType=" + objectTypeName(sourceObject));
        }

        if (!PinStore.isSupportedPlaylistId(playlistId)) {
            speedDialPlaylistId =
                    resolveNativeSpeedDialPlaylistId(flyoutMenu);

            if (PinStore.isSupportedPlaylistId(speedDialPlaylistId)) {
                playlistId = speedDialPlaylistId;

                Log.d(TAG, "FlyoutPageIdentityBridge"
                        + " resolved=true"
                        + " resolutionSource=nativeSpeedDialCommand"
                        + " playlistId=" + playlistId
                        + " sourceType="
                        + objectTypeName(sourceObject));
            }
        }

        if (!PinStore.isSupportedPlaylistId(playlistId)) {
            menuGraphPlaylistId =
                    resolvePageMenuGraphPlaylistId(
                            flyoutMenu,
                            flyoutPresenter
                    );

            if (PinStore.isSupportedPlaylistId(menuGraphPlaylistId)) {
                playlistId = menuGraphPlaylistId;

                Log.d(TAG, "FlyoutPageIdentityBridge"
                        + " resolved=true"
                        + " resolutionSource=pageMenuGraphs"
                        + " playlistId=" + playlistId
                        + " sourceType="
                        + objectTypeName(sourceObject));
            }
        }

        if (!PinStore.isSupportedPlaylistId(playlistId)) {
            clearActiveFlyoutRowContext();

            Log.d(TAG, "Flyout source playlist id not found"
                    + " sourceType=" + objectTypeName(sourceObject)
                    + " viewBridgeAvailable="
                    + PinStore.isSupportedPlaylistId(viewPlaylistId)
                    + " speedDialBridgeAvailable="
                    + PinStore.isSupportedPlaylistId(speedDialPlaylistId)
                    + " menuGraphBridgeAvailable="
                    + PinStore.isSupportedPlaylistId(menuGraphPlaylistId));
            logCandidateSet(
                    "FlyoutSourceCandidate",
                    sourceStrings
            );
            return;
        }

        if (!playlistId.equals(activeFlyoutRowPlaylistId)) {
            clearActiveFlyoutRowContext();
        }

        indexFlyoutObjectGraph(
                flyoutMenu,
                playlistId
        );

        if (flyoutPresenter != null) {
            synchronized (flyoutPresenterIds) {
                flyoutPresenterIds.put(
                        flyoutPresenter,
                        playlistId
                );
            }
        }

        activeFlyoutPlaylistId = playlistId;
        activeFlyoutCapturedAtMs =
                android.os.SystemClock.elapsedRealtime();

        Log.d(TAG, "Captured flyout playlist id=" + playlistId
                + " presenterMapped=" + (flyoutPresenter != null)
                + " activeBridge=true"
                + " rowContextMatched="
                + playlistId.equals(activeFlyoutRowPlaylistId)
                + " sourceType=" + objectTypeName(sourceObject)
                + " menuType=" + objectTypeName(flyoutMenu)
                + " presenterType=" + objectTypeName(flyoutPresenter));
    }

    /**
     * Adds a Library-specific Pin/Unpin row directly below the native Speed
     * Dial row. The row is cloned from the active renderer variant so it keeps
     * native styling, while identity/title tracking distinguishes its local
     * action from the stock Speed Dial command.
     */
    @Nullable
    public static Object prepareFlyoutMenu(
            @Nullable Object flyoutMenu,
            @Nullable Object sourceObject
    ) {
        if (!isFeatureEnabled()
                || !isSeparateMenuItemEnabled()
                || flyoutMenu == null) {
            return flyoutMenu;
        }

        try {
            Object listObject =
                    readFieldByName(flyoutMenu, "c");

            if (!(listObject instanceof List)) {
                Log.d(TAG, "SeparatePinMenuRow skipped"
                        + " reason=noMenuList"
                        + " menuType="
                        + objectTypeName(flyoutMenu));
                return flyoutMenu;
            }

            String playlistId =
                    activeFlyoutPlaylistId;

            if (!PinStore.isSupportedPlaylistId(playlistId)) {
                playlistId = findBestPlaylistId(
                        collectObjectStrings(sourceObject)
                );
            }

            /*
             * Both Library-row and playlist-page menus are supported, but
             * injection still requires a canonical playlist ID. The page path
             * receives that identity from the earlier qot.k View hook; all
             * unrelated menus remain untouched.
             */
            if (!PinStore.isSupportedPlaylistId(playlistId)) {
                Log.d(TAG, "SeparatePinMenuRow skipped"
                        + " reason=noCanonicalPlaylistId"
                        + " sourceType="
                        + objectTypeName(sourceObject));
                return flyoutMenu;
            }

            /*
             * qot.j can reuse the bwyr instance supplied in p1 when the
             * playlist menu is reopened. Never make that reusable protobuf
             * mutable. Work on a builder-owned copy and return the detached
             * message to the bytecode hook instead.
             */
            Object menuBuilder =
                    invokeNoArgObject(
                            flyoutMenu,
                            "toBuilder"
                    );

            if (menuBuilder == null
                    || !invokeNoArgVoid(
                    menuBuilder,
                    "copyOnWrite"
            )) {
                Log.d(TAG, "SeparatePinMenuRow skipped"
                        + " reason=menuCopyFailed"
                        + " menuType="
                        + objectTypeName(flyoutMenu));
                return flyoutMenu;
            }

            Object menuCopyInstance =
                    readFieldByName(
                            menuBuilder,
                            "instance"
                    );

            if (menuCopyInstance == null) {
                Log.d(TAG, "SeparatePinMenuRow skipped"
                        + " reason=noMenuCopyInstance"
                        + " builderType="
                        + objectTypeName(menuBuilder));
                return flyoutMenu;
            }

            /* bwyr.a() detaches its repeated menu-item list for mutation. */
            if (!invokeNoArgVoid(menuCopyInstance, "a")) {
                Log.d(TAG, "SeparatePinMenuRow skipped"
                        + " reason=menuCopyFailed"
                        + " stage=mutableList"
                        + " menuType="
                        + objectTypeName(menuCopyInstance));
                return flyoutMenu;
            }

            Object mutableListObject =
                    readFieldByName(menuCopyInstance, "c");

            if (!(mutableListObject instanceof List)) {
                Log.d(TAG, "SeparatePinMenuRow skipped"
                        + " reason=noMutableMenuList");
                return flyoutMenu;
            }

            List mutableItems =
                    (List) mutableListObject;

            int removedExistingRows = 0;

            for (int index = mutableItems.size() - 1;
                 index >= 0;
                 index--) {
                Object item = mutableItems.get(index);

                if (!isInjectedLibraryPinMenuItem(item)) {
                    continue;
                }

                mutableItems.remove(index);
                removedExistingRows++;
            }

            int speedDialIndex = -1;

            for (int index = 0;
                 index < mutableItems.size();
                 index++) {
                Object item = mutableItems.get(index);

                if (isSpeedDialMenuItem(item)) {
                    speedDialIndex = index;
                    break;
                }
            }

            Context context = resolveApplicationContext();
            boolean pinned =
                    context != null
                            && playlistId != null
                            && PinStore.isPinned(
                                    context,
                                    playlistId
                            );

            String title = pinned
                    ? MENU_TITLE_UNPIN
                    : MENU_TITLE_PIN;

            int cloneSourceIndex = speedDialIndex;
            Object clonedItem = null;

            if (cloneSourceIndex >= 0) {
                clonedItem = cloneLibraryPinMenuItem(
                        mutableItems.get(cloneSourceIndex),
                        title,
                        pinned
                );
            } else {
                /*
                 * Some accounts or flyout-filter settings omit Speed Dial.
                 * Any native row using the same menu presenter is a safe
                 * visual template: the cloned command is never dispatched,
                 * because handleClick consumes the injected row by identity
                 * and by its rewritten pin icon/title.
                 */
                for (int index = 0;
                     index < mutableItems.size();
                     index++) {
                    Object candidate = mutableItems.get(index);

                    if (isInjectedLibraryPinMenuItem(candidate)) {
                        continue;
                    }

                    clonedItem = cloneLibraryPinMenuItem(
                            candidate,
                            title,
                            pinned
                    );

                    if (clonedItem != null) {
                        cloneSourceIndex = index;
                        break;
                    }
                }
            }

            if (clonedItem == null) {
                Log.d(TAG, "SeparatePinMenuRow skipped"
                        + " reason=cloneFailed"
                        + " removedExistingRows="
                        + removedExistingRows);
                return flyoutMenu;
            }

            int insertionIndex =
                    Math.min(
                            cloneSourceIndex + 1,
                            mutableItems.size()
                    );

            mutableItems.add(
                    insertionIndex,
                    clonedItem
            );

            Object detachedMenu =
                    invokeNoArgObject(
                            menuBuilder,
                            "build"
                    );

            if (detachedMenu == null) {
                Log.d(TAG, "SeparatePinMenuRow skipped"
                        + " reason=menuCopyBuildFailed"
                        + " builderType="
                        + objectTypeName(menuBuilder));
                return flyoutMenu;
            }

            Log.d(TAG, "SeparatePinMenuRow"
                    + " added=true"
                    + " detachedCopy=true"
                    + " refreshed="
                    + (removedExistingRows > 0)
                    + " removedExistingRows="
                    + removedExistingRows
                    + " template="
                    + (speedDialIndex >= 0
                    ? "speedDial"
                    : "fallback")
                    + " insertionIndex=" + insertionIndex
                    + " pinned=" + pinned
                    + " playlistId=" + playlistId
                    + " title=" + title);

            return detachedMenu;
        } catch (Throwable error) {
            Log.e(TAG, "Failed adding separate Library pin menu row", error);
            return flyoutMenu;
        }
    }

    private static boolean isSpeedDialMenuItem(
            @Nullable Object menuItem
    ) {
        if (menuItem == null) return false;

        try {
            Object iconMessage =
                    invokeStaticByNames(
                            MENU_ITEM_HELPER_CLASSES,
                            "d",
                            menuItem
                    );

            Object iconNumberObject =
                    readFieldByName(
                            iconMessage,
                            "c"
                    );

            if (!(iconNumberObject instanceof Integer)) {
                return false;
            }

            Object iconEnum =
                    invokeStaticByNames(
                            ICON_ENUM_CLASSES,
                            "a",
                            iconNumberObject
                    );

            if (!(iconEnum instanceof Enum)) {
                return false;
            }

            String name =
                    ((Enum<?>) iconEnum).name();

            return name.equals("KEEP")
                    || name.equals("PIN_OUTLINE")
                    || name.equals("KEEP_OFF")
                    || name.equals("PIN_OFF_OUTLINE");
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean isInjectedLibraryPinMenuItem(
            @Nullable Object menuItem
    ) {
        if (menuItem == null) return false;

        synchronized (injectedLibraryPinMenuItems) {
            if (injectedLibraryPinMenuItems.containsKey(menuItem)) {
                return true;
            }
        }

        /*
         * Some protobuf/render paths may copy the inserted item. The custom
         * title is therefore a safe structural fallback for click routing.
         */
        Object resolvedTitle =
                invokeStaticByNames(
                        MENU_ITEM_HELPER_CLASSES,
                        "e",
                        menuItem
                );

        if (resolvedTitle == null) return false;

        String title = resolvedTitle.toString();

        return title.equals(MENU_TITLE_PIN)
                || title.equals(MENU_TITLE_UNPIN);
    }

    private static void rememberInjectedLibraryPinMenuItem(
            Object menuItem
    ) {
        synchronized (injectedLibraryPinMenuItems) {
            if (injectedLibraryPinMenuItems.size()
                    >= MAX_TRACKED_MENU_ITEMS) {
                injectedLibraryPinMenuItems.clear();
            }

            injectedLibraryPinMenuItems.put(
                    menuItem,
                    Boolean.TRUE
            );
        }
    }

    /**
     * The stock presenter treats bwzj rows as native toggle items and may
     * replace their model from sharedToggleMenuItemMutations immediately
     * before binding the title. For our cloned row, preserve the model state
     * already written by prepareFlyoutMenu; for every native row, return the
     * stock state unchanged.
     */
    public static boolean guardNativeToggleMutation(
            @Nullable Object presenter,
            boolean nativeState,
            boolean modelState
    ) {
        try {
            return guardNativeToggleMutationInternal(
                    presenter,
                    nativeState,
                    modelState
            );
        } catch (Throwable error) {
            Log.e(TAG, "Failed guarding native menu toggle", error);
            return nativeState;
        }
    }

    private static boolean guardNativeToggleMutationInternal(
            @Nullable Object presenter,
            boolean nativeState,
            boolean modelState
    ) {
        Object menuItem =
                findPresenterMenuItem(presenter);
        boolean injected =
                isInjectedLibraryPinMenuItem(menuItem);

        if (injected
                && nativeToggleGuardLogCount < 8) {
            nativeToggleGuardLogCount++;

            Log.d(TAG, "SeparatePinToggleGuard"
                    + " applied=true"
                    + " nativeState=" + nativeState
                    + " modelState=" + modelState);
        }

        return injected
                ? modelState
                : nativeState;
    }

    private static boolean hasAnySimpleClassName(
            @Nullable Object value,
            String... expectedNames
    ) {
        if (value == null) return false;

        String actual = value.getClass().getSimpleName();

        for (String expected : expectedNames) {
            if (expected.equals(actual)) return true;
        }

        return false;
    }

    @Nullable
    private static Object findPresenterMenuItem(
            @Nullable Object presenter
    ) {
        if (presenter == null) return null;

        Object direct =
                readFieldByName(
                        presenter,
                        "c"
                );

        if (hasAnySimpleClassName(direct, "bwyn", "buzr")) {
            return direct;
        }

        /*
         * Field names are obfuscated. Keep a structural fallback so a future
         * version only needs a Kotlin fingerprint update when the presenter
         * still contains exactly one bwyn model under a different field name.
         */
        for (Field field :
                getInstanceFields(presenter.getClass())) {
            if (field.getType().isPrimitive()) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(presenter);

                if (hasAnySimpleClassName(value, "bwyn", "buzr")) {
                    return value;
                }
            } catch (Throwable ignored) {
                // Try the next field.
            }
        }

        return null;
    }

    @Nullable
    private static Object cloneLibraryPinMenuItem(
            Object sourceItem,
            String title,
            boolean pinned
    ) {
        Object resolvedTitle =
                invokeStaticByNames(
                        MENU_ITEM_HELPER_CLASSES,
                        "e",
                        sourceItem
                );

        Object resolvedIcon =
                invokeStaticByNames(
                        MENU_ITEM_HELPER_CLASSES,
                        "d",
                        sourceItem
                );

        MenuComponentSelection selection =
                findActiveMenuComponent(
                        sourceItem,
                        resolvedTitle,
                        resolvedIcon
                );

        if (selection == null) {
            logSeparatePinCloneFailure(
                    "activeVariant",
                    sourceItem,
                    null
            );
            logDirectObjectFields(
                    "SeparatePinVariantProbe",
                    sourceItem
            );
            return null;
        }

        Object sourceComponent =
                selection.sourceComponent;

        Log.d(TAG, "SeparatePinComponent"
                + " selected=true"
                + " parentField="
                + (selection.parentField == null
                ? "<item>"
                : selection.parentField.getName())
                + " componentType="
                + objectTypeName(sourceComponent)
                + " titleField="
                + selection.titleField.getName()
                + " iconField="
                + selection.iconField.getName()
                + " score=" + selection.score);

        Object componentBuilder =
                invokeNoArgObject(
                        sourceComponent,
                        "toBuilder"
                );

        if (componentBuilder == null) {
            logSeparatePinCloneFailure(
                    "componentBuilder",
                    sourceItem,
                    sourceComponent
            );
            return null;
        }

        if (!invokeNoArgVoid(
                componentBuilder,
                "copyOnWrite"
        )) {
            logSeparatePinCloneFailure(
                    "componentCopyOnWrite",
                    sourceItem,
                    sourceComponent
            );
            return null;
        }

        Object componentInstance =
                readFieldByName(
                        componentBuilder,
                        "instance"
                );

        if (componentInstance == null) {
            logSeparatePinCloneFailure(
                    "componentInstance",
                    sourceItem,
                    sourceComponent
            );
            return null;
        }

        Object textMessage =
                createSimpleTextMessage(title);

        if (textMessage == null) {
            logSeparatePinCloneFailure(
                    "title",
                    sourceItem,
                    sourceComponent
            );
            return null;
        }

        Object sourceIcon;

        try {
            selection.iconField.setAccessible(true);
            sourceIcon =
                    selection.iconField.get(
                            sourceComponent
                    );
        } catch (Throwable error) {
            Log.e(
                    TAG,
                    "Could not read active Speed Dial icon field",
                    error
            );
            logSeparatePinCloneFailure(
                    "sourceIconRead",
                    sourceItem,
                    sourceComponent
            );
            return null;
        }

        Object iconMessage =
                clonePinMenuIconMessage(
                        sourceIcon,
                        pinned
                );

        if (iconMessage == null) {
            logSeparatePinCloneFailure(
                    "icon",
                    sourceItem,
                    sourceComponent
            );
            return null;
        }

        boolean componentWrites =
                writeFieldByName(
                        componentInstance,
                        selection.titleField.getName(),
                        textMessage
                )
                        && writeFieldByName(
                        componentInstance,
                        selection.iconField.getName(),
                        iconMessage
                );

        if (!componentWrites) {
            logSeparatePinCloneFailure(
                    "componentWrites",
                    sourceItem,
                    sourceComponent
            );
            return null;
        }

        Object componentClone =
                invokeNoArgObject(
                        componentBuilder,
                        "build"
                );

        if (componentClone == null) {
            logSeparatePinCloneFailure(
                    "componentBuild",
                    sourceItem,
                    sourceComponent
            );
            return null;
        }

        Object clonedItem;

        if (selection.parentField == null) {
            clonedItem = componentClone;
        } else {
            Object itemBuilder =
                    invokeNoArgObject(
                            sourceItem,
                            "toBuilder"
                    );

            if (itemBuilder == null) {
                logSeparatePinCloneFailure(
                        "itemBuilder",
                        sourceItem,
                        sourceComponent
                );
                return null;
            }

            if (!invokeNoArgVoid(
                    itemBuilder,
                    "copyOnWrite"
            )) {
                logSeparatePinCloneFailure(
                        "itemCopyOnWrite",
                        sourceItem,
                        sourceComponent
                );
                return null;
            }

            Object itemInstance =
                    readFieldByName(
                            itemBuilder,
                            "instance"
                    );

            if (itemInstance == null) {
                logSeparatePinCloneFailure(
                        "itemInstance",
                        sourceItem,
                        sourceComponent
                );
                return null;
            }

            if (!writeFieldByName(
                    itemInstance,
                    selection.parentField.getName(),
                    componentClone
            )) {
                logSeparatePinCloneFailure(
                        "itemComponentWrite",
                        sourceItem,
                        sourceComponent
                );
                return null;
            }

            clonedItem =
                    invokeNoArgObject(
                            itemBuilder,
                            "build"
                    );
        }

        if (clonedItem == null) {
            logSeparatePinCloneFailure(
                    "itemBuild",
                    sourceItem,
                    sourceComponent
            );
            return null;
        }

        rememberInjectedLibraryPinMenuItem(
                clonedItem
        );

        Log.d(TAG, "SeparatePinClone"
                + " success=true"
                + " itemType=" + objectTypeName(clonedItem)
                + " componentType=" + objectTypeName(componentClone)
                + " parentField="
                + (selection.parentField == null
                ? "<item>"
                : selection.parentField.getName())
                + " pinned=" + pinned
                + " title=" + title);

        return clonedItem;
    }

    @Nullable
    private static MenuComponentSelection findActiveMenuComponent(
            Object sourceItem,
            @Nullable Object resolvedTitle,
            @Nullable Object resolvedIcon
    ) {
        String resolvedTitleString =
                resolvedTitle == null
                        ? null
                        : resolvedTitle.toString();

        Integer resolvedIconNumber =
                readIntegerField(
                        resolvedIcon,
                        "c"
                );

        MenuComponentSelection best =
                evaluateMenuComponentCandidate(
                        null,
                        sourceItem,
                        resolvedTitleString,
                        resolvedIconNumber
                );

        for (Field parentField :
                getInstanceFields(sourceItem.getClass())) {
            if (parentField.getType().isPrimitive()) {
                continue;
            }

            Object candidate;

            try {
                parentField.setAccessible(true);
                candidate =
                        parentField.get(sourceItem);
            } catch (Throwable ignored) {
                continue;
            }

            if (candidate == null) continue;

            MenuComponentSelection selection =
                    evaluateMenuComponentCandidate(
                            parentField,
                            candidate,
                            resolvedTitleString,
                            resolvedIconNumber
                    );

            if (selection != null
                    && (best == null
                    || selection.score > best.score)) {
                best = selection;
            }
        }

        return best;
    }

    @Nullable
    private static MenuComponentSelection evaluateMenuComponentCandidate(
            @Nullable Field parentField,
            Object candidate,
            @Nullable String resolvedTitle,
            @Nullable Integer resolvedIconNumber
    ) {
        Field firstTitleField = null;
        Field exactTitleField = null;
        Field firstIconField = null;
        Field exactIconField = null;
        int commandCount = 0;

        for (Field field :
                getInstanceFields(candidate.getClass())) {
            if (field.getType().isPrimitive()) {
                continue;
            }

            Object value;

            try {
                field.setAccessible(true);
                value = field.get(candidate);
            } catch (Throwable ignored) {
                continue;
            }

            if (value == null) continue;

            String typeName =
                    value.getClass().getSimpleName();

            if (typeName.equals("bsmc")
                    || typeName.equals("bqph")) {
                if (firstTitleField == null) {
                    firstTitleField = field;
                }

                String rendered =
                        renderTextMessage(value);

                if (resolvedTitle != null
                        && resolvedTitle.equals(rendered)) {
                    exactTitleField = field;
                }
            } else if (typeName.equals("btcx")
                    || typeName.equals("brga")) {
                if (firstIconField == null) {
                    firstIconField = field;
                }

                Integer iconNumber =
                        readIntegerField(
                                value,
                                "c"
                        );

                if (resolvedIconNumber != null
                        && resolvedIconNumber.equals(iconNumber)) {
                    exactIconField = field;
                }
            } else if (typeName.equals("bqco")
                    || typeName.equals("boht")) {
                commandCount++;
            }
        }

        Field titleField =
                exactTitleField != null
                        ? exactTitleField
                        : firstTitleField;

        Field iconField =
                exactIconField != null
                        ? exactIconField
                        : firstIconField;

        if (titleField == null
                || iconField == null) {
            return null;
        }

        int score = 2;

        if (exactTitleField != null) {
            score += 4;
        }

        if (exactIconField != null) {
            score += 4;
        }

        if (commandCount > 0) {
            score += 1;
        }

        return new MenuComponentSelection(
                parentField,
                candidate,
                titleField,
                iconField,
                score
        );
    }

    private static List<Field> getInstanceFields(
            Class<?> type
    ) {
        ArrayList<Field> fields =
                new ArrayList<>();

        for (Class<?> current = type;
             current != null
                     && current != Object.class;
             current = current.getSuperclass()) {
            Field[] declaredFields =
                    current.getDeclaredFields();

            for (Field field : declaredFields) {
                if (Modifier.isStatic(
                        field.getModifiers()
                ) || field.isSynthetic()) {
                    continue;
                }

                fields.add(field);
            }
        }

        return fields;
    }

    @Nullable
    private static Integer readIntegerField(
            @Nullable Object receiver,
            String fieldName
    ) {
        Object value =
                readFieldByName(
                        receiver,
                        fieldName
                );

        return value instanceof Integer
                ? (Integer) value
                : null;
    }

    @Nullable
    private static String renderTextMessage(
            Object textMessage
    ) {
        Object rendered =
                invokeStaticByNames(
                        TEXT_HELPER_CLASSES,
                        "b",
                        textMessage
                );

        return rendered == null
                ? null
                : rendered.toString();
    }

    private static void logDirectObjectFields(
            String prefix,
            Object receiver
    ) {
        StringBuilder output =
                new StringBuilder(prefix)
                        .append(" receiverType=")
                        .append(objectTypeName(receiver));

        for (Field field :
                getInstanceFields(receiver.getClass())) {
            if (field.getType().isPrimitive()) {
                continue;
            }

            Object value;

            try {
                field.setAccessible(true);
                value = field.get(receiver);
            } catch (Throwable ignored) {
                continue;
            }

            output.append(" ")
                    .append(field.getName())
                    .append("=")
                    .append(objectTypeName(value));
        }

        Log.d(TAG, output.toString());
    }

    private static void logSeparatePinCloneFailure(
            String stage,
            @Nullable Object sourceItem,
            @Nullable Object sourceComponent
    ) {
        Log.d(TAG, "SeparatePinClone"
                + " success=false"
                + " stage=" + stage
                + " itemType=" + objectTypeName(sourceItem)
                + " componentType="
                + objectTypeName(sourceComponent));
    }

    @Nullable
    private static Object createSimpleTextMessage(
            String text
    ) {
        Object message =
                invokeStaticByNames(
                        TEXT_HELPER_CLASSES,
                        "f",
                        text
                );

        if (message == null) {
            Log.d(TAG, "SeparatePinClone"
                    + " success=false"
                    + " stage=textFactory"
                    + " text=" + text);
        }

        return message;
    }

    @Nullable
    private static Object clonePinMenuIconMessage(
            @Nullable Object sourceIcon,
            boolean pinned
    ) {
        if (sourceIcon == null) {
            Log.d(TAG, "SeparatePinClone"
                    + " success=false"
                    + " stage=noSourceIcon");
            return null;
        }

        try {
            Object iconEnum =
                    findEnumConstant(
                            ICON_ENUM_CLASSES,
                            pinned
                                    ? "PIN_OFF_OUTLINE"
                                    : "PIN_OUTLINE"
                    );

            if (iconEnum == null) {
                Log.d(TAG, "SeparatePinClone"
                        + " success=false"
                        + " stage=iconEnum");
                return null;
            }

            Object numberObject =
                    invokeNoArgObject(
                            iconEnum,
                            "getNumber"
                    );

            if (!(numberObject instanceof Integer)) {
                Log.d(TAG, "SeparatePinClone"
                        + " success=false"
                        + " stage=iconNumber"
                        + " enum=" + iconEnum);
                return null;
            }

            Object iconBuilder =
                    invokeNoArgObject(
                            sourceIcon,
                            "toBuilder"
                    );

            if (iconBuilder == null) {
                Log.d(TAG, "SeparatePinClone"
                        + " success=false"
                        + " stage=iconBuilder"
                        + " iconType="
                        + objectTypeName(sourceIcon));
                return null;
            }

            if (!invokeNoArgVoid(
                    iconBuilder,
                    "copyOnWrite"
            )) {
                Log.d(TAG, "SeparatePinClone"
                        + " success=false"
                        + " stage=iconCopyOnWrite");
                return null;
            }

            Object iconInstance =
                    readFieldByName(
                            iconBuilder,
                            "instance"
                    );

            if (iconInstance == null) {
                Log.d(TAG, "SeparatePinClone"
                        + " success=false"
                        + " stage=iconInstance");
                return null;
            }

            int iconBits = 0;
            Object iconBitsObject =
                    readFieldByName(
                            iconInstance,
                            "b"
                    );

            if (iconBitsObject instanceof Integer) {
                iconBits = (Integer) iconBitsObject;
            }

            if (!writeFieldByName(
                    iconInstance,
                    "c",
                    numberObject
            ) || !writeFieldByName(
                    iconInstance,
                    "b",
                    iconBits | 0x1
            )) {
                Log.d(TAG, "SeparatePinClone"
                        + " success=false"
                        + " stage=iconWrites");
                return null;
            }

            Object iconClone =
                    invokeNoArgObject(
                            iconBuilder,
                            "build"
                    );

            if (iconClone == null) {
                Log.d(TAG, "SeparatePinClone"
                        + " success=false"
                        + " stage=iconBuild");
            }

            return iconClone;
        } catch (Throwable error) {
            Log.e(
                    TAG,
                    "Failed cloning Library pin menu icon",
                    error
            );
            return null;
        }
    }

    private static final class MenuComponentSelection {
        @Nullable
        final Field parentField;
        final Object sourceComponent;
        final Field titleField;
        final Field iconField;
        final int score;

        MenuComponentSelection(
                @Nullable Field parentField,
                Object sourceComponent,
                Field titleField,
                Field iconField,
                int score
        ) {
            this.parentField = parentField;
            this.sourceComponent = sourceComponent;
            this.titleField = titleField;
            this.iconField = iconField;
            this.score = score;
        }
    }

    private static void showPinStateToast(
            Context context,
            boolean pinned
    ) {
        Context appContext =
                context.getApplicationContext();

        Toast.makeText(
                appContext != null
                        ? appContext
                        : context,
                pinned
                        ? TOAST_PINNED
                        : TOAST_UNPINNED,
                Toast.LENGTH_SHORT
        ).show();
    }

    /**
     * Hijacks only the existing Pin/Unpin Speed Dial rows.
     */
    public static boolean handleClick(
            @Nullable View clickedView,
            @Nullable Object presenter
    ) {
        try {
            Object menuItem = findPresenterMenuItem(presenter);
            Object iconMessage = invokeStaticByNames(
                    MENU_ITEM_HELPER_CLASSES,
                    "d",
                    menuItem
            );
            Object iconNumber = readFieldByName(iconMessage, "c");
            Object icon = invokeStaticByNames(
                    ICON_ENUM_CLASSES,
                    "a",
                    iconNumber
            );

            return icon instanceof Enum<?>
                    && handleClick(
                    clickedView,
                    (Enum<?>) icon,
                    presenter,
                    menuItem
            );
        } catch (Throwable error) {
            Log.e(TAG, "Failed resolving menu click", error);
            return false;
        }
    }

    /**
     * Performs the pin action after the presenter-specific click data is resolved.
     */
    public static boolean handleClick(
            @Nullable View clickedView,
            @Nullable Enum<?> icon,
            @Nullable Object presenter,
            @Nullable Object menuItem
    ) {
        if (!isFeatureEnabled()) {
            return false;
        }

        boolean injectedMenuItem =
                isInjectedLibraryPinMenuItem(menuItem);
        boolean separateMenuItem =
                isSeparateMenuItemEnabled();

        if (separateMenuItem && !injectedMenuItem) {
            return false;
        }

        if (!separateMenuItem && injectedMenuItem) {
            return false;
        }

        if (icon == null) return false;

        String name = icon.name();

        boolean isPin =
                name.equals("KEEP") ||
                name.equals("PIN_OUTLINE");

        boolean isUnpin =
                name.equals("KEEP_OFF") ||
                name.equals("PIN_OFF_OUTLINE");

        if (!isPin && !isUnpin) return false;

        if (clickedView != null) {
            Context context = clickedView.getContext();
            if (context != null) {
                applicationContext = context.getApplicationContext();
            }
        }

        String key = lookupPlaylistIdFromPresenterGraph(presenter);

        if (key != null) {
            Log.d(TAG, "Resolved playlist id from mapped flyout presenter=" + key);
        } else {
            key = lookupFlyoutPlaylistId(menuItem);
        }

        if (key == null) {
            key = consumeActiveFlyoutPlaylistId();
            if (key != null) {
                Log.d(TAG, "Resolved playlist id from active flyout bridge=" + key);
            }
        }

        if (!PinStore.isSupportedPlaylistId(key)) {
            Log.e(TAG, "Library pin click ignored"
                    + " reason=noCanonicalPlaylistId"
                    + " injected=" + injectedMenuItem
                    + " presenterType=" + objectTypeName(presenter)
                    + " menuItemType=" + objectTypeName(menuItem));

            /*
             * Consume a stray injected row so its cloned native command can
             * never run. Stock rows fall through to the original app action.
             */
            return injectedMenuItem;
        }

        Log.d(TAG, (injectedMenuItem
                ? "Handled separate Library pin item"
                : "Hijacked Speed Dial item")
                + " icon=" + name
                + " key=" + key
                + " rowPosition=" + activeFlyoutAdapterPosition
                + " stableRowId=" + activeFlyoutStableRowId
                + " model=" + menuItem);

        if (clickedView == null) {
            Log.e(TAG, "Cannot persist pin state: clicked View is null");
            return true;
        }

        Context clickContext =
                clickedView.getContext();
        boolean newPinnedState =
                PinStore.togglePinned(clickContext, key);

        showPinStateToast(
                clickContext,
                newPinnedState
        );

        refreshProcessPinPresence(
                clickContext
        );

        /*
         * On a zero-pin cold start, visible rows were intentionally not
         * tracked before the first pin. Repaint the exact Library row that
         * opened this flyout so the subtitle pin appears immediately even
         * when the full position-map refresh is unavailable.
         */
        boolean matchingRowContext =
                key.equals(activeFlyoutRowPlaylistId);

        postDirectFlyoutRowPinIndicatorRefresh(
                matchingRowContext
                        ? activeFlyoutRowView
                        : null,
                newPinnedState,
                key
        );

        boolean refreshedFactoryMap =
                refreshActiveFactoryPositionMap(
                        clickedView.getContext()
                );

        if (!refreshedFactoryMap) {
            clearAllAdapterPositionRemaps("pinToggleFallback");

            Object adapter = activeLibraryAdapter;
            Long rowId = matchingRowContext
                    ? activeFlyoutStableRowId
                    : null;

            if (adapter != null && rowId != null) {
                rememberAdapterPlaylistId(
                        adapter,
                        rowId,
                        key
                );
                scheduleAdapterReorder(
                        adapter,
                        clickedView
                );
            } else {
                Log.d(TAG, "Pin state stored, but active Library"
                        + " mapping was unavailable for"
                        + " immediate reorder");
            }
        }

        Log.d(TAG, newPinnedState
                ? "Stored local playlist pin"
                : "Removed local playlist pin");

        return true;
    }

    /**
     * Stable-partitions raw section-list rows into:
     *
     * [matching pinned rows in existing order] + [all other rows in existing order]
     *
     * The optional metadata list is moved with exactly the same permutation.
     * The controller argument is currently retained for logging/future narrowing;
     * context is resolved independently so this also works after an app restart.
     */





    private static Set<String> collectCanonicalPlaylistIds(
            @Nullable Object root,
            int maxDepth
    ) {
        Set<String> ids = new LinkedHashSet<>();
        if (root == null) return ids;

        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        int[] visitedCount = new int[]{0};

        collectCanonicalPlaylistIdsRecursive(
                root,
                ids,
                visited,
                visitedCount,
                0,
                maxDepth
        );

        return ids;
    }

    private static void collectCanonicalPlaylistIdsRecursive(
            @Nullable Object value,
            Set<String> ids,
            IdentityHashMap<Object, Boolean> visited,
            int[] visitedCount,
            int depth,
            int maxDepth
    ) {
        if (value == null || depth > maxDepth) return;
        if (visitedCount[0] >= 500 || ids.size() >= 12) return;

        if (value instanceof CharSequence) {
            String id = extractCanonicalPlaylistId(value.toString());
            if (id != null) ids.add(id);
            return;
        }

        byte[] bytes = extractByteContainer(value);
        if (bytes != null && bytes.length != 0) {
            Set<String> strings = new LinkedHashSet<>();
            addPrintableCandidates(bytes, strings);
            for (String candidate : strings) {
                String id = extractCanonicalPlaylistId(candidate);
                if (id != null) ids.add(id);
            }

            if (value instanceof byte[] || isByteContainer(value.getClass())) {
                return;
            }
        }

        Class<?> type = value.getClass();
        if (isTerminalType(type) || type.isPrimitive()) return;

        if (visited.put(value, Boolean.TRUE) != null) return;
        visitedCount[0]++;

        if (type.isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                collectCanonicalPlaylistIdsRecursive(
                        Array.get(value, index),
                        ids,
                        visited,
                        visitedCount,
                        depth + 1,
                        maxDepth
                );
            }
            return;
        }

        if (value instanceof Iterable) {
            for (Object child : (Iterable<?>) value) {
                collectCanonicalPlaylistIdsRecursive(
                        child,
                        ids,
                        visited,
                        visitedCount,
                        depth + 1,
                        maxDepth
                );
            }
            return;
        }

        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                collectCanonicalPlaylistIdsRecursive(
                        entry.getKey(),
                        ids,
                        visited,
                        visitedCount,
                        depth + 1,
                        maxDepth
                );
                collectCanonicalPlaylistIdsRecursive(
                        entry.getValue(),
                        ids,
                        visited,
                        visitedCount,
                        depth + 1,
                        maxDepth
                );
            }
            return;
        }

        for (Class<?> current = type;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable error) {
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType().isPrimitive()) continue;

                try {
                    field.setAccessible(true);
                    collectCanonicalPlaylistIdsRecursive(
                            field.get(value),
                            ids,
                            visited,
                            visitedCount,
                            depth + 1,
                            maxDepth
                    );
                } catch (Throwable ignored) {
                }
            }
        }
    }


    private static Set<String> collectObjectStrings(@Nullable Object root) {
        Set<String> candidates = new LinkedHashSet<>();

        if (root != null) {
            IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
            int[] visitedCount = new int[]{0};

            collectDiagnosticStringsRecursive(
                    root,
                    candidates,
                    visited,
                    visitedCount,
                    0
            );
        }

        return candidates;
    }

    @Nullable
    private static String findBestPlaylistId(Set<String> candidates) {
        if (candidates == null) return null;

        String best = null;

        for (String candidate : candidates) {
            if (candidate == null) continue;

            Matcher matcher = PLAYLIST_ID_PATTERN.matcher(candidate);
            while (matcher.find()) {
                String id = matcher.group(1);

                if (best == null
                        || id.length() < best.length()
                        || (id.length() == best.length()
                        && candidate.equals(id))) {
                    best = id;
                }
            }

            if (best == null
                    && (candidate.contains("VLLM")
                    || candidate.equals("LM")
                    || candidate.startsWith("LM"))) {
                best = "LM";
            }
        }

        return best;
    }


    @Nullable
    private static String extractCanonicalPlaylistId(@Nullable String text) {
        if (text == null) return null;

        Matcher matcher = PLAYLIST_ID_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // YouTube Music's built-in Liked Music playlist commonly appears as
        // VLLM / LM rather than a normal PL... identifier.
        if (text.contains("VLLM") || text.equals("LM") || text.startsWith("LM")) {
            return "LM";
        }

        return null;
    }

    private static void indexFlyoutObjectGraph(
            @Nullable Object root,
            String playlistId
    ) {
        if (root == null) return;

        synchronized (flyoutObjectIds) {
            if (flyoutObjectIds.size() > MAX_FLYOUT_OBJECT_MAPPINGS) {
                flyoutObjectIds.clear();
            }

            IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
            int[] visitedCount = new int[]{0};
            indexFlyoutObjectGraphRecursive(
                    root,
                    playlistId,
                    visited,
                    visitedCount,
                    0
            );
        }
    }

    private static void indexFlyoutObjectGraphRecursive(
            @Nullable Object value,
            String playlistId,
            IdentityHashMap<Object, Boolean> visited,
            int[] visitedCount,
            int depth
    ) {
        if (value == null || depth > MAX_REFLECTION_DEPTH) return;
        if (visitedCount[0] >= MAX_VISITED_OBJECTS_PER_ROW) return;

        Class<?> type = value.getClass();
        if (isTerminalType(type) || type.isPrimitive()) return;
        if (value instanceof byte[] || isByteContainer(type)) return;

        if (visited.put(value, Boolean.TRUE) != null) return;
        visitedCount[0]++;

        flyoutObjectIds.put(value, playlistId);

        if (type.isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                indexFlyoutObjectGraphRecursive(
                        Array.get(value, index),
                        playlistId,
                        visited,
                        visitedCount,
                        depth + 1
                );
            }
            return;
        }

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                indexFlyoutObjectGraphRecursive(
                        item,
                        playlistId,
                        visited,
                        visitedCount,
                        depth + 1
                );
            }
            return;
        }

        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                indexFlyoutObjectGraphRecursive(
                        entry.getKey(),
                        playlistId,
                        visited,
                        visitedCount,
                        depth + 1
                );
                indexFlyoutObjectGraphRecursive(
                        entry.getValue(),
                        playlistId,
                        visited,
                        visitedCount,
                        depth + 1
                );
            }
            return;
        }

        for (Class<?> current = type;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable error) {
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType().isPrimitive()) continue;

                try {
                    field.setAccessible(true);
                    indexFlyoutObjectGraphRecursive(
                            field.get(value),
                            playlistId,
                            visited,
                            visitedCount,
                            depth + 1
                    );
                } catch (Throwable ignored) {
                }
            }
        }
    }


    @Nullable
    private static Object readFieldByName(
            @Nullable Object receiver,
            String fieldName
    ) {
        if (receiver == null) return null;

        for (Class<?> current = receiver.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                if (Modifier.isStatic(field.getModifiers())) continue;

                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable error) {
                return null;
            }
        }

        return null;
    }

    private static boolean writeFieldByName(
            @Nullable Object receiver,
            String fieldName,
            @Nullable Object value
    ) {
        if (receiver == null) return false;

        for (Class<?> current = receiver.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field =
                        current.getDeclaredField(fieldName);

                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                field.setAccessible(true);
                field.set(receiver, value);
                return true;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable error) {
                return false;
            }
        }

        return false;
    }


    @Nullable
    private static Object invokeNoArgObject(
            @Nullable Object receiver,
            String methodName
    ) {
        if (receiver == null) return null;

        for (Class<?> current = receiver.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Method method =
                        current.getDeclaredMethod(methodName);

                if (method.getParameterTypes().length != 0
                        || method.getReturnType() == Void.TYPE) {
                    continue;
                }

                method.setAccessible(true);
                return method.invoke(receiver);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable error) {
                return null;
            }
        }

        return null;
    }

    @Nullable
    private static Object invokeStaticByName(
            String className,
            String methodName,
            Object... arguments
    ) {
        try {
            Class<?> type =
                    Class.forName(className);

            for (Method method :
                    type.getDeclaredMethods()) {
                if (!Modifier.isStatic(
                        method.getModifiers()
                ) || !method.getName().equals(methodName)
                        || method.getParameterTypes().length
                        != arguments.length) {
                    continue;
                }

                Class<?>[] parameterTypes =
                        method.getParameterTypes();
                boolean compatible = true;

                for (int index = 0;
                     index < parameterTypes.length;
                     index++) {
                    Object argument =
                            arguments[index];

                    if (argument == null) {
                        if (parameterTypes[index].isPrimitive()) {
                            compatible = false;
                            break;
                        }
                        continue;
                    }

                    Class<?> parameterType =
                            wrapPrimitiveType(
                                    parameterTypes[index]
                            );

                    if (!parameterType.isInstance(argument)) {
                        compatible = false;
                        break;
                    }
                }

                if (!compatible) continue;

                method.setAccessible(true);
                return method.invoke(
                        null,
                        arguments
                );
            }
        } catch (Throwable error) {
            return null;
        }

        return null;
    }

    @Nullable
    private static Object invokeStaticByNames(
            String[] classNames,
            String methodName,
            Object... arguments
    ) {
        for (String className : classNames) {
            Object result = invokeStaticByName(
                    className,
                    methodName,
                    arguments
            );

            if (result != null) return result;
        }

        return null;
    }

    @Nullable
    private static Object findEnumConstant(
            String[] classNames,
            String constantName
    ) {
        for (String className : classNames) {
            try {
                Class<?> enumClass = Class.forName(className);

                @SuppressWarnings("unchecked")
                Object constant = Enum.valueOf(
                        (Class<? extends Enum>)
                                enumClass.asSubclass(Enum.class),
                        constantName
                );

                return constant;
            } catch (Throwable ignored) {
                // Try the symbol set for the other supported channel.
            }
        }

        return null;
    }

    private static Class<?> wrapPrimitiveType(
            Class<?> type
    ) {
        if (!type.isPrimitive()) return type;
        if (type == Integer.TYPE) return Integer.class;
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Byte.TYPE) return Byte.class;
        if (type == Short.TYPE) return Short.class;
        if (type == Float.TYPE) return Float.class;
        if (type == Double.TYPE) return Double.class;
        if (type == Character.TYPE) return Character.class;
        return type;
    }

    private static String abbreviateDiagnosticValue(
            @Nullable String value
    ) {
        if (value == null) return "null";
        if (value.length() <= 220) return value;
        return value.substring(0, 217) + "...";
    }

    /**
     * Runs after hyz.o(ViewHolder, position) has bound a row. The playlist
     * source object is attached to the row's overflow View as a normal or keyed
     * tag, so this discovers the canonical playlist ID without opening the
     * menu. Exact IDs are associated with hvg.b, the adapter's stable row ID.
     */
    /**
     * Called at the start and normal return points of hyz.o. The entry call
     * preserves adapter identity; the return call inspects the fully bound row
     * synchronously, avoiding the old per-row View.post delay.
     */
    private static boolean isFeatureEnabled() {
        Boolean enabled = lastFeatureEnabledState;

        if (enabled == null) {
            synchronized (featureStateLock) {
                enabled = lastFeatureEnabledState;

                if (enabled == null) {
                    enabled = true;

                    /*
                     * Selecting the patch is the feature toggle. Keep the
                     * runtime extension independent of Morphe's settings
                     * bundle so both patch bundles can be applied together.
                     */
                    lastFeatureEnabledState = enabled;

                    Log.d(TAG, "PinPlaylistFeatureStartup"
                            + " enabled=" + enabled
                            + " restartRequired=true");
                }
            }
        }

        ensureFeatureStoreStateSynchronized(enabled);
        return enabled;
    }

    private static void ensureFeatureStoreStateSynchronized(
            boolean enabled
    ) {
        if (featureStoreStateSynchronized) return;

        Context context = resolveApplicationContext();
        if (context == null) return;

        synchronized (featureStateLock) {
            if (featureStoreStateSynchronized) return;

            boolean clearedPins =
                    PinStore.synchronizeFeatureState(
                            context,
                            enabled
                    );

            featureStoreStateSynchronized = true;

            if (clearedPins) {
                processHasAnyPins = false;
                activeFlyoutPlaylistId = null;
                activeFlyoutCapturedAtMs = 0L;

                Log.d(TAG, "PinPlaylistFreshStart"
                        + " clearedPins=true"
                        + " reason=disabledToEnabled");
            }
        }
    }

    private static boolean isSeparateMenuItemEnabled() {
        Boolean enabled = lastSeparateMenuItemEnabledState;
        if (enabled != null) return enabled;

        synchronized (featureStateLock) {
            enabled = lastSeparateMenuItemEnabledState;
            if (enabled != null) return enabled;

            enabled = true;

            lastSeparateMenuItemEnabledState = enabled;

            Log.d(TAG, "PinPlaylistSeparateMenuStartup"
                    + " enabled=" + enabled
                    + " restartRequired=true");

            return enabled;
        }
    }


    private static boolean hasAnyPinsFast() {
        Boolean cached = processHasAnyPins;
        if (cached != null) return cached;

        Context context = resolveApplicationContext();

        /*
         * Fail open if Context is not ready yet. This preserves correctness;
         * the next hook will retry after Application initialization.
         */
        if (context == null) return true;

        boolean hasPins = PinStore.hasAnyPins(context);
        processHasAnyPins = hasPins;

        if (!hasPins && !noPinColdStartBypassLogged) {
            noPinColdStartBypassLogged = true;
            Log.d(TAG, "DiagnosticBuild=" + BUILD_ID
                    + " noPinColdStartBypass=true");
        }

        return hasPins;
    }

    private static void refreshProcessPinPresence(
            @Nullable Context context
    ) {
        if (context == null) {
            processHasAnyPins = null;
            return;
        }

        processHasAnyPins =
                PinStore.hasAnyPins(context);
    }

    private static boolean captureActiveFactoryContext(
            @Nullable Object owner
    ) {
        if (owner == null
                || !"bfrh".equals(objectTypeName(owner))) {
            return false;
        }

        Object controller = readFieldByName(owner, "o");
        Object visualAdapter = controller == null
                ? null
                : readFieldByName(controller, "f");

        if (!isLibraryAdapter(visualAdapter)) {
            return false;
        }

        boolean changed =
                owner != activeAdapterProxyFactoryOwner
                        || visualAdapter
                        != activeAdapterProxyFactoryVisualAdapter;

        activeAdapterProxyFactoryOwner = owner;
        activeAdapterProxyFactoryVisualAdapter =
                visualAdapter;

        if (changed && noPinFactoryContextLogCount < 8) {
            noPinFactoryContextLogCount++;

            Log.d(TAG, "NoPinFactoryContextCaptured"
                    + " ownerIdentity="
                    + identityString(owner)
                    + " visualAdapterIdentity="
                    + identityString(visualAdapter));
        }

        return true;
    }

    public static int remapAdapterProxySourcePosition(
            @Nullable Object owner,
            int visualPosition
    ) {
        if (!isFeatureEnabled()) {
            return visualPosition;
        }

        if (owner == null
                || visualPosition < 0
                || !"bfrh".equals(objectTypeName(owner))) {
            return visualPosition;
        }

        if (!hasAnyPinsFast()) {
            /*
             * Keep the clean-install path cheap, but retain the current bfrh
             * owner and hyz adapter. When the first playlist is pinned, the
             * click handler can build and install the position map immediately
             * instead of waiting for an app restart.
             */
            captureActiveFactoryContext(owner);
            return visualPosition;
        }

        /*
         * A complete rebuild begins at position zero. Only that invocation
         * performs source discovery and canonical-ID extraction. All later
         * h(index) calls use the already-installed hyz position map.
         *
         * This avoids the v53 pattern that rescanned the entire growing bewt
         * source at counts 10, 11, 12, 13, 14 and 15.
         */
        if (visualPosition != 0) {
            return visualPosition;
        }

        Object sourceAdapter = readFieldByName(owner, "p");
        Integer sourceCount =
                invokeIntNoArg(sourceAdapter, "a");

        if (sourceAdapter == null
                || sourceCount == null
                || sourceCount < 10
                || sourceCount > 24) {
            if (adapterProxyFactoryInstallLogCount < 12) {
                adapterProxyFactoryInstallLogCount++;

                Log.d(TAG, "PreFactoryFastPathSkipped"
                        + " reason=entryValidation"
                        + " ownerType=" + objectTypeName(owner)
                        + " sourceAdapterType="
                        + objectTypeName(sourceAdapter)
                        + " sourceCount=" + sourceCount);
            }

            return visualPosition;
        }

        int[] visualToSource =
                buildAdapterProxyFactoryPositionMap(
                        owner,
                        sourceAdapter,
                        sourceCount
                );

        if (visualToSource == null) {
            synchronized (adapterProxyFactoryVisualToSource) {
                adapterProxyFactoryVisualToSource.remove(owner);
                adapterProxyFactorySourceCounts.remove(owner);
            }
            return visualPosition;
        }

        Object controller = readFieldByName(owner, "o");
        Object visualAdapter = controller == null
                ? null
                : readFieldByName(controller, "f");

        if (!isLibraryAdapter(visualAdapter)) {
            if (adapterProxyFactoryInstallLogCount < 12) {
                adapterProxyFactoryInstallLogCount++;

                Log.d(TAG, "PreFactoryFastPathSkipped"
                        + " reason=visualAdapterUnavailable"
                        + " controllerType="
                        + objectTypeName(controller)
                        + " visualAdapterType="
                        + objectTypeName(visualAdapter));
            }

            return visualPosition;
        }

        synchronized (adapterProxyFactoryVisualToSource) {
            adapterProxyFactoryVisualToSource.put(
                    owner,
                    visualToSource
            );
            adapterProxyFactorySourceCounts.put(
                    owner,
                    sourceCount
            );
        }

        synchronized (adapterVisualToSourcePositions) {
            adapterVisualToSourcePositions.clear();
            adapterVisualToSourcePositions.put(
                    visualAdapter,
                    visualToSource.clone()
            );
        }

        installAdapterPinIndicatorMetadata(
                owner,
                visualAdapter
        );

        activeAdapterProxyFactoryOwner = owner;
        activeAdapterProxyFactoryVisualAdapter = visualAdapter;

        Log.d(TAG, "PreFactoryFastPath"
                + " installed=true"
                + " ownerIdentity=" + identityString(owner)
                + " visualAdapterIdentity="
                + identityString(visualAdapter)
                + " sourceCount=" + sourceCount
                + " visualToSource="
                + java.util.Arrays.toString(
                visualToSource
        ));

        return visualPosition;
    }

    @Nullable
    private static int[] buildAdapterProxyFactoryPositionMap(
            Object owner,
            Object sourceAdapter,
            int sourceCount
    ) {
        Context context = resolveApplicationContext();
        if (context == null) {
            if (adapterProxyFactoryMapLogCount < 24) {
                adapterProxyFactoryMapLogCount++;
                Log.d(TAG, "PreFactoryPositionMapSkipped"
                        + " reason=noContext"
                        + " count=" + sourceCount);
            }
            return null;
        }

        List<String> pinOrder =
                new ArrayList<>(
                        PinStore.getPinnedIds(context)
                );

        if (pinOrder.isEmpty()) return null;

        List<Object> sourceItems =
                new ArrayList<>(sourceCount);
        LinkedHashMap<Integer, String> playlistIdByPosition =
                new LinkedHashMap<>();
        LinkedHashMap<String, Integer> sourcePositionById =
                new LinkedHashMap<>();
        Set<String> knownPlaylistIds =
                new LinkedHashSet<>(pinOrder);

        Map<String, String> persistedSignatures =
                PinStore.getPlaylistSignatures(context);
        knownPlaylistIds.addAll(
                persistedSignatures.keySet()
        );

        for (int position = 0;
             position < sourceCount;
             position++) {
            Object sourceItem =
                    invokeOneIntArgument(
                            sourceAdapter,
                            "getItem",
                            position
                    );

            sourceItems.add(sourceItem);

            if (sourceItem == null) continue;

            Set<String> canonicalIds =
                    collectCanonicalPlaylistIds(
                            sourceItem,
                            10
                    );

            LinkedHashSet<String> persistentIds =
                    new LinkedHashSet<>();

            for (String candidate : canonicalIds) {
                if (isPersistentPlaylistId(candidate)) {
                    persistentIds.add(candidate);
                }
            }

            String playlistId =
                    resolveCanonicalPlaylistId(
                            persistentIds,
                            pinOrder,
                            knownPlaylistIds
                    );

            if (!isPersistentPlaylistId(playlistId)) {
                continue;
            }

            if (sourcePositionById.containsKey(playlistId)) {
                Log.d(TAG, "PreFactoryPositionMapSkipped"
                        + " reason=duplicatePlaylistId"
                        + " playlistId=" + playlistId
                        + " firstPosition="
                        + sourcePositionById.get(playlistId)
                        + " duplicatePosition=" + position);
                return null;
            }

            playlistIdByPosition.put(
                    position,
                    playlistId
            );
            sourcePositionById.put(
                    playlistId,
                    position
            );
        }

        /*
         * The Library currently has ten ordinary playlist rows. Keep this
         * threshold broad enough for additions/deletions while rejecting
         * unrelated bfrh-backed lists.
         */
        if (playlistIdByPosition.size() < 3) {
            if (adapterProxyFactoryMapLogCount < 24) {
                adapterProxyFactoryMapLogCount++;
                Log.d(TAG, "PreFactoryPositionMapSkipped"
                        + " reason=tooFewPlaylistRows"
                        + " count=" + sourceCount
                        + " mapped="
                        + playlistIdByPosition.size()
                        + " sourceTypes="
                        + sourceTypeSummary(sourceItems));
            }
            return null;
        }

        List<Integer> playlistSlots =
                new ArrayList<>(
                        playlistIdByPosition.keySet()
                );

        List<Integer> desiredPlaylistSources =
                new ArrayList<>();
        Set<Integer> pinnedSourcePositions =
                new LinkedHashSet<>();
        List<String> pinnedPresent =
                new ArrayList<>();

        for (String pinnedId : pinOrder) {
            Integer sourcePosition =
                    sourcePositionById.get(pinnedId);

            if (sourcePosition == null) continue;

            desiredPlaylistSources.add(sourcePosition);
            pinnedSourcePositions.add(sourcePosition);
            pinnedPresent.add(pinnedId);
        }

        if (pinnedPresent.isEmpty()) {
            return null;
        }

        /*
         * Retain the current native order for all unpinned playlist rows.
         */
        for (int sourcePosition : playlistSlots) {
            if (!pinnedSourcePositions.contains(
                    sourcePosition
            )) {
                desiredPlaylistSources.add(
                        sourcePosition
                );
            }
        }

        if (desiredPlaylistSources.size()
                != playlistSlots.size()) {
            Log.d(TAG, "PreFactoryPositionMapSkipped"
                    + " reason=playlistCountMismatch"
                    + " slots=" + playlistSlots
                    + " desired="
                    + desiredPlaylistSources);
            return null;
        }

        int[] visualToSource =
                new int[sourceCount];

        for (int position = 0;
             position < sourceCount;
             position++) {
            visualToSource[position] = position;
        }

        for (int index = 0;
             index < playlistSlots.size();
             index++) {
            visualToSource[
                    playlistSlots.get(index)
            ] = desiredPlaylistSources.get(index);
        }

        LinkedHashMap<Integer, String>
                visualPlaylistIds =
                new LinkedHashMap<>();
        LinkedHashSet<Integer>
                pinnedVisualPositions =
                new LinkedHashSet<>();

        for (int index = 0;
             index < playlistSlots.size();
             index++) {
            int visualPosition =
                    playlistSlots.get(index);
            int sourcePosition =
                    desiredPlaylistSources.get(index);
            String playlistId =
                    playlistIdByPosition.get(sourcePosition);

            if (playlistId == null) continue;

            visualPlaylistIds.put(
                    visualPosition,
                    playlistId
            );

            if (pinnedSourcePositions.contains(
                    sourcePosition
            )) {
                pinnedVisualPositions.add(
                        visualPosition
                );
            }
        }

        synchronized (ownerVisualPlaylistIds) {
            ownerVisualPlaylistIds.put(
                    owner,
                    visualPlaylistIds
            );
            ownerPinnedVisualPositions.put(
                    owner,
                    pinnedVisualPositions
            );
        }

        boolean[] seen = new boolean[sourceCount];

        for (int sourcePosition : visualToSource) {
            if (sourcePosition < 0
                    || sourcePosition >= sourceCount
                    || seen[sourcePosition]) {
                Log.d(TAG, "PreFactoryPositionMapSkipped"
                        + " reason=notPermutation"
                        + " visualToSource="
                        + java.util.Arrays.toString(
                        visualToSource
                ));
                return null;
            }

            seen[sourcePosition] = true;
        }

        Log.d(TAG, "PreFactoryPositionMap"
                + " installed=true"
                + " ownerIdentity="
                + identityString(owner)
                + " sourceAdapterType="
                + objectTypeName(sourceAdapter)
                + " count=" + sourceCount
                + " playlistSlots=" + playlistSlots
                + " pinnedPresent=" + pinnedPresent
                + " visualToSource="
                + java.util.Arrays.toString(
                visualToSource
        ));

        return visualToSource;
    }

    private static List<String> sourceTypeSummary(
            List<Object> sourceItems
    ) {
        List<String> result = new ArrayList<>();

        if (sourceItems == null) return result;

        for (Object sourceItem : sourceItems) {
            result.add(objectTypeName(sourceItem));
        }

        return result;
    }

    public static void beginAdapterProxyRenderInfo(
            @Nullable Object owner,
            int sourceIndex
    ) {
        if (!isFeatureEnabled()
                || !hasAnyPinsFast()) {
            return;
        }

        if (!adapterProxyHookLogged) {
            adapterProxyHookLogged = true;
            Log.d(TAG, "DiagnosticBuild=" + BUILD_ID
                    + " adapterProxySourceHook=true");
        }

        ArrayList<AdapterProxySource> stack =
                pendingAdapterProxySources.get();

        stack.add(
                new AdapterProxySource(owner, sourceIndex)
        );
    }

    public static void captureAdapterProxySourceObject(
            @Nullable Object sourceObject
    ) {
        if (!isFeatureEnabled()) {
            pendingAdapterProxySources.remove();
            return;
        }

        ArrayList<AdapterProxySource> stack =
                pendingAdapterProxySources.get();

        if (stack.isEmpty()) return;

        AdapterProxySource source =
                stack.get(stack.size() - 1);

        source.sourceObject = sourceObject;
    }

    public static void captureAdapterProxyRenderInfo(
            @Nullable Object renderInfo
    ) {
        if (!isFeatureEnabled()) {
            pendingAdapterProxySources.remove();
            return;
        }

        ArrayList<AdapterProxySource> stack =
                pendingAdapterProxySources.get();

        if (stack.isEmpty()) return;

        AdapterProxySource source =
                stack.remove(stack.size() - 1);

        if (stack.isEmpty()) {
            pendingAdapterProxySources.remove();
        }

        if (source.owner == null
                || !"bfrh".equals(
                objectTypeName(source.owner)
        )
                || renderInfo == null
                || !"hyi".equals(objectTypeName(renderInfo))) {
            return;
        }

        Integer sourceCount = null;
        Object sourceAdapter =
                readFieldByName(source.owner, "p");

        if (sourceAdapter != null) {
            sourceCount =
                    invokeIntNoArg(sourceAdapter, "a");
        }

        if (sourceCount != null
                && hasActiveFactoryMapForOwner(
                        source.owner,
                        sourceCount
                )) {
            return;
        }

        source.renderInfo = renderInfo;
        source.completedAtMs = SystemClock.uptimeMillis();

        synchronized (adapterProxySources) {
            adapterProxySources.put(renderInfo, source);

            if (adapterProxySources.size() > 1600) {
                adapterProxySources.clear();
                adapterProxySources.put(renderInfo, source);
            }
        }

        synchronized (adapterProxySourceHistory) {
            ArrayList<AdapterProxySource> history =
                    adapterProxySourceHistory.get(source.owner);

            if (history == null) {
                history = new ArrayList<>();
                adapterProxySourceHistory.put(
                        source.owner,
                        history
                );
            }

            history.add(source);

            if (history.size() > 600) {
                history.subList(0, history.size() - 300)
                        .clear();
            }
        }
    }

    public static void beginAdapterProxyReplaceAll(
            @Nullable Object owner
    ) {
        if (!isFeatureEnabled()
                || !hasAnyPinsFast()) {
            pendingAdapterProxyOwner.remove();
            return;
        }

        if (!adapterProxyHookLogged) {
            adapterProxyHookLogged = true;
            Log.d(TAG, "DiagnosticBuild=" + BUILD_ID
                    + " adapterProxySourceHook=true");
        }

        if (owner == null
                || !"bfrh".equals(objectTypeName(owner))) {
            pendingAdapterProxyOwner.remove();
            return;
        }

        pendingAdapterProxyOwner.set(owner);
    }

    public static void prepareAdapterProxyRenderInfos(
            @Nullable Object renderInfoList
    ) {
        Object owner = pendingAdapterProxyOwner.get();
        pendingAdapterProxyOwner.remove();

        if (!adapterProxyHookLogged) {
            adapterProxyHookLogged = true;
            Log.d(TAG, "DiagnosticBuild=" + BUILD_ID
                    + " adapterProxySourceHook=true");
        }

        if (owner == null
                || !(renderInfoList instanceof List)
                || !"bfrh".equals(objectTypeName(owner))) {
            return;
        }

        if (!isFeatureEnabled()
                || !hasAnyPinsFast()) {
            return;
        }

        List rawList = (List) renderInfoList;
        int total = rawList.size();

        if (hasActiveFactoryMapForOwner(
                owner,
                total
        )) {
            if (adapterProxyFactoryInstallLogCount < 20) {
                adapterProxyFactoryInstallLogCount++;

                Log.d(TAG, "PreSubmitFastPathSkipped"
                        + " reason=preFactoryMapActive"
                        + " ownerIdentity="
                        + identityString(owner)
                        + " total=" + total);
            }
            return;
        }

        int proxyRows = 0;

        for (Object item : rawList) {
            if ("hyi".equals(objectTypeName(item))) {
                proxyRows++;
            }
        }

        List<AdapterProxySource> orderedSources =
                findLatestAdapterProxySourceBatch(
                        owner,
                        total
                );

        int sourcedRows = orderedSources.size();

        if (total >= 6 && adapterProxyAttemptLogCount < 60) {
            adapterProxyAttemptLogCount++;

            Log.d(TAG, "PreSubmitEntry"
                    + " ownerIdentity=" + identityString(owner)
                    + " total=" + total
                    + " proxyRows=" + proxyRows
                    + " sourcedRows=" + sourcedRows
                    + " expectedPlaylistRows="
                    + lastKnownLibraryPlaylistCount);
        }

        /*
         * Library submissions are normally 14-15 rows, but incremental source
         * mutations can be slightly smaller. Keep a narrow enough range to
         * exclude recommendation shelves while still catching the active B/S
         * insertion paths.
         */
        if (total < 6 || total > 24) return;
        if (proxyRows < 6 || sourcedRows < 6) return;

        Context context = resolveApplicationContext();
        if (context == null) return;

        List<String> pinOrder =
                new ArrayList<>(PinStore.getPinnedIds(context));
        if (pinOrder.isEmpty()) {
            clearAdapterPositionRemapForOwner(
                    owner,
                    "noPins"
            );
            return;
        }

        AdapterProxyMapping mapping =
                chooseAdapterProxyDirectSourceMapping(
                        orderedSources,
                        rawList,
                        context,
                        pinOrder
                );

        if (adapterProxyAttemptLogCount < 100) {
            adapterProxyAttemptLogCount++;

            Log.d(TAG, "PreSubmitMapping"
                    + " total=" + total
                    + " proxyRows=" + proxyRows
                    + " sourcedRows=" + sourcedRows
                    + " expectedPlaylistRows="
                    + lastKnownLibraryPlaylistCount
                    + " strategy=orderedDirectSource"
                    + " selectedPath="
                    + (mapping == null ? null : mapping.path)
                    + " offset="
                    + (mapping == null ? 0 : mapping.offset)
                    + " score="
                    + (mapping == null ? 0 : mapping.score)
                    + " mappedRows="
                    + (mapping == null
                    ? 0
                    : mapping.idsByListPosition.size())
                    + " ambiguous="
                    + (mapping != null && mapping.ambiguous)
                    + " pinOrder=" + pinOrder);
        }

        if (mapping == null
                || mapping.ambiguous
                || mapping.idsByListPosition.size() < 6) {
            clearAdapterPositionRemapForOwner(
                    owner,
                    "mappingUnavailable"
            );
            return;
        }

        LinkedHashMap<String, Object> rawItemById =
                new LinkedHashMap<>();
        LinkedHashMap<String, Object> sourceItemById =
                new LinkedHashMap<>();
        List<Integer> playlistSlots = new ArrayList<>();

        for (Map.Entry<Integer, String> entry
                : mapping.idsByListPosition.entrySet()) {
            int position = entry.getKey();
            String playlistId = entry.getValue();

            if (position < 0
                    || position >= rawList.size()
                    || position >= orderedSources.size()) {
                continue;
            }

            if (!isPersistentPlaylistId(playlistId)) continue;
            if (sourceItemById.containsKey(playlistId)) continue;

            AdapterProxySource source =
                    orderedSources.get(position);
            if (source == null || source.sourceObject == null) {
                continue;
            }

            rawItemById.put(playlistId, rawList.get(position));
            sourceItemById.put(
                    playlistId,
                    source.sourceObject
            );
            playlistSlots.add(position);
        }

        Collections.sort(playlistSlots);

        List<Object> desiredRawItems = new ArrayList<>();
        List<Object> desiredSourceItems = new ArrayList<>();
        Set<String> pinnedPresent = new LinkedHashSet<>();

        for (String pinnedId : pinOrder) {
            Object sourceItem = sourceItemById.get(pinnedId);
            if (sourceItem == null) continue;

            desiredSourceItems.add(sourceItem);
            desiredRawItems.add(rawItemById.get(pinnedId));
            pinnedPresent.add(pinnedId);
        }

        if (pinnedPresent.isEmpty()) return;

        for (int position : playlistSlots) {
            String playlistId =
                    mapping.idsByListPosition.get(position);
            if (playlistId == null
                    || pinnedPresent.contains(playlistId)) {
                continue;
            }

            Object sourceItem =
                    sourceItemById.get(playlistId);
            if (sourceItem != null) {
                desiredSourceItems.add(sourceItem);
                desiredRawItems.add(
                        rawItemById.get(playlistId)
                );
            }
        }

        if (desiredSourceItems.size() != playlistSlots.size()) {
            Log.d(TAG, "PreSubmitSkipped reason=incompleteDesiredOrder"
                    + " slots=" + playlistSlots
                    + " desired=" + desiredSourceItems.size()
                    + " mapping=" + mapping.idsByListPosition);
            return;
        }

        boolean positionRemapInstalled =
                installAdapterPositionRemap(
                        owner,
                        orderedSources,
                        playlistSlots,
                        desiredSourceItems
                );

        /*
         * All delegated playlist entries share the same Lhyi instance, so
         * assigning those objects within rawList is a no-op. The installed
         * hyz position translation is the operation that controls the first
         * visible bind.
         */
        boolean proxyListChanged = false;

        List<String> resultingIds = new ArrayList<>();
        for (Object sourceItem : desiredSourceItems) {
            String id = null;

            for (Map.Entry<String, Object> entry
                    : sourceItemById.entrySet()) {
                if (entry.getValue() == sourceItem) {
                    id = entry.getKey();
                    break;
                }
            }

            resultingIds.add(id);
        }

        Log.d(TAG, "PreSubmitApplied"
                + " changed="
                + (positionRemapInstalled || proxyListChanged)
                + " positionRemapInstalled="
                + positionRemapInstalled
                + " sourceMatched=false"
                + " sourceChanged=false"
                + " sourcePath=null"
                + " sourceMode=virtualPositionRemap"
                + " proxyListChanged=" + proxyListChanged
                + " total=" + total
                + " playlistSlots=" + playlistSlots
                + " pinnedPresent=" + pinnedPresent
                + " identityPath=" + mapping.path
                + " resultingIds=" + resultingIds);
    }


    private static boolean installAdapterPositionRemap(
            Object owner,
            List<AdapterProxySource> orderedSources,
            List<Integer> playlistSlots,
            List<Object> desiredSourceItems
    ) {
        Object controller = readFieldByName(owner, "o");
        Object visualAdapter = controller == null
                ? null
                : readFieldByName(controller, "f");
        Object sourceAdapter = readFieldByName(owner, "p");

        if (!isLibraryAdapter(visualAdapter)
                || sourceAdapter == null
                || !"bewt".equals(objectTypeName(sourceAdapter))) {
            Log.d(TAG, "PreSubmitPositionMapSkipped"
                    + " reason=adapterResolution"
                    + " ownerType=" + objectTypeName(owner)
                    + " controllerType="
                    + objectTypeName(controller)
                    + " visualAdapterType="
                    + objectTypeName(visualAdapter)
                    + " sourceAdapterType="
                    + objectTypeName(sourceAdapter));
            return false;
        }

        int total = orderedSources.size();
        if (total <= 0
                || playlistSlots.size()
                != desiredSourceItems.size()) {
            return false;
        }

        IdentityHashMap<Object, Integer> sourcePositionByItem =
                new IdentityHashMap<>();

        for (AdapterProxySource source : orderedSources) {
            if (source == null
                    || source.sourceObject == null
                    || source.sourceIndex < 0
                    || source.sourceIndex >= total) {
                Log.d(TAG, "PreSubmitPositionMapSkipped"
                        + " reason=invalidSourceFrame"
                        + " total=" + total);
                return false;
            }

            sourcePositionByItem.put(
                    source.sourceObject,
                    source.sourceIndex
            );
        }

        int[] visualToSource = new int[total];
        for (int position = 0; position < total; position++) {
            visualToSource[position] = position;
        }

        for (int index = 0;
             index < playlistSlots.size();
             index++) {
            int visualPosition = playlistSlots.get(index);
            Object desiredSourceItem =
                    desiredSourceItems.get(index);
            Integer sourcePosition =
                    sourcePositionByItem.get(desiredSourceItem);

            if (visualPosition < 0
                    || visualPosition >= total
                    || sourcePosition == null
                    || sourcePosition < 0
                    || sourcePosition >= total) {
                Log.d(TAG, "PreSubmitPositionMapSkipped"
                        + " reason=unresolvedPosition"
                        + " visualPosition=" + visualPosition
                        + " sourcePosition=" + sourcePosition);
                return false;
            }

            visualToSource[visualPosition] = sourcePosition;
        }

        boolean[] seen = new boolean[total];
        for (int sourcePosition : visualToSource) {
            if (sourcePosition < 0
                    || sourcePosition >= total
                    || seen[sourcePosition]) {
                Log.d(TAG, "PreSubmitPositionMapSkipped"
                        + " reason=notPermutation"
                        + " visualToSource="
                        + java.util.Arrays.toString(
                        visualToSource
                ));
                return false;
            }

            seen[sourcePosition] = true;
        }

        synchronized (adapterVisualToSourcePositions) {
            /*
             * A newly completed Library submission supersedes older adapter
             * instances. Keeping only the active map also prevents stale maps
             * from affecting a later pin/unpin physical move.
             */
            adapterVisualToSourcePositions.clear();
            adapterVisualToSourcePositions.put(
                    visualAdapter,
                    visualToSource
            );
        }

        Log.d(TAG, "PreSubmitPositionMap"
                + " installed=true"
                + " visualAdapterType="
                + objectTypeName(visualAdapter)
                + " visualAdapterIdentity="
                + identityString(visualAdapter)
                + " sourceAdapterType="
                + objectTypeName(sourceAdapter)
                + " visualToSource="
                + java.util.Arrays.toString(
                visualToSource
        ));

        return true;
    }

    private static void clearAdapterPositionRemapForOwner(
            @Nullable Object owner,
            String reason
    ) {
        if (owner == null) return;

        Object controller = readFieldByName(owner, "o");
        Object visualAdapter = controller == null
                ? null
                : readFieldByName(controller, "f");

        if (visualAdapter == null) return;

        boolean removed;
        synchronized (adapterVisualToSourcePositions) {
            removed =
                    adapterVisualToSourcePositions.remove(
                            visualAdapter
                    ) != null;
        }

        if (removed) {
            Log.d(TAG, "AdapterPositionMapCleared"
                    + " reason=" + reason
                    + " adapterIdentity="
                    + identityString(visualAdapter));
        }
    }

    private static void clearAllAdapterPositionRemaps(
            String reason
    ) {
        int count;

        synchronized (adapterVisualToSourcePositions) {
            count = adapterVisualToSourcePositions.size();
            adapterVisualToSourcePositions.clear();
        }

        synchronized (adapterProxyFactoryVisualToSource) {
            adapterProxyFactoryVisualToSource.clear();
            adapterProxyFactorySourceCounts.clear();
        }

        synchronized (ownerVisualPlaylistIds) {
            ownerVisualPlaylistIds.clear();
            ownerPinnedVisualPositions.clear();
        }

        synchronized (adapterVisualPlaylistIds) {
            adapterVisualPlaylistIds.clear();
            adapterPinnedVisualPositions.clear();
        }

        synchronized (adapterVisibleRowViews) {
            adapterVisibleRowViews.clear();
        }

        clearAllVisiblePinIndicators();

        activeAdapterProxyFactoryOwner = null;
        activeAdapterProxyFactoryVisualAdapter = null;

        pendingAdapterPositionRemapTarget.remove();
        pendingAdapterPositionRemapKind.remove();

        if (count > 0) {
            Log.d(TAG, "AdapterPositionMapsCleared"
                    + " reason=" + reason
                    + " count=" + count);
        }
    }

    private static void rememberVisibleBoundRow(
            @Nullable Object adapter,
            int visualPosition,
            @Nullable View itemView
    ) {
        if (adapter == null
                || itemView == null
                || visualPosition < 0) {
            return;
        }

        synchronized (adapterVisibleRowViews) {
            Map<Integer, View> rows =
                    adapterVisibleRowViews.get(adapter);

            if (rows == null) {
                rows = new LinkedHashMap<>();
                adapterVisibleRowViews.put(
                        adapter,
                        rows
                );
            }

            ArrayList<Integer> stalePositions =
                    new ArrayList<>();

            for (Map.Entry<Integer, View> entry :
                    rows.entrySet()) {
                if (entry.getValue() == itemView
                        && entry.getKey()
                        != visualPosition) {
                    stalePositions.add(entry.getKey());
                }
            }

            for (Integer stalePosition : stalePositions) {
                rows.remove(stalePosition);
            }

            rows.put(visualPosition, itemView);

            if (rows.size() > 64) {
                rows.clear();
                rows.put(visualPosition, itemView);
            }
        }
    }

    private static int refreshVisiblePinIndicators(
            @Nullable Object adapter
    ) {
        if (adapter == null) return 0;

        Map<Integer, View> rows;

        synchronized (adapterVisibleRowViews) {
            Map<Integer, View> stored =
                    adapterVisibleRowViews.get(adapter);

            if (stored == null || stored.isEmpty()) {
                return 0;
            }

            rows = new LinkedHashMap<>(stored);
        }

        int refreshed = 0;

        for (Map.Entry<Integer, View> entry :
                rows.entrySet()) {
            View rowView = entry.getValue();
            if (rowView == null) continue;

            if (!rowView.isAttachedToWindow()) {
                continue;
            }

            applyBoundRowPinIndicator(
                    adapter,
                    rowView,
                    entry.getKey()
            );
            refreshed++;
        }

        return refreshed;
    }

    private static void runVisiblePinIndicatorRefreshPass(
            @Nullable Object adapter,
            String pass
    ) {
        int refreshed =
                refreshVisiblePinIndicators(adapter);

        Log.d(TAG, "VisiblePinIndicatorRefresh"
                + " pass=" + pass
                + " adapterIdentity="
                + identityString(adapter)
                + " refreshedRows=" + refreshed);
    }

    private static void postVisiblePinIndicatorRefresh(
            @Nullable Object adapter
    ) {
        if (adapter == null) return;

        mainHandler.post(
                () -> runVisiblePinIndicatorRefreshPass(
                        adapter,
                        "immediate"
                )
        );

        mainHandler.postDelayed(
                () -> runVisiblePinIndicatorRefreshPass(
                        adapter,
                        "afterLayout"
                ),
                48L
        );

        mainHandler.postDelayed(
                () -> runVisiblePinIndicatorRefreshPass(
                        adapter,
                        "settled"
                ),
                160L
        );
    }

    private static boolean applyDirectFlyoutRowPinIndicator(
            @Nullable View itemView,
            boolean pinned,
            @Nullable String playlistId,
            String pass
    ) {
        if (itemView == null) return false;

        RowTextViews rowTextViews =
                findRowTextViews(itemView);

        if (rowTextViews == null
                || rowTextViews.subtitle == null) {
            Log.d(TAG, "DirectFlyoutPinIndicatorRefresh"
                    + " pass=" + pass
                    + " applied=false"
                    + " reason=noSubtitle"
                    + " playlistId=" + playlistId);
            return false;
        }

        stripLegacyPinPrefix(rowTextViews.title);
        stripLegacyPinPrefix(rowTextViews.subtitle);

        TextView subtitleView =
                rowTextViews.subtitle;
        Drawable[] current =
                subtitleView.getCompoundDrawablesRelative();

        if (pinned) {
            synchronized (activePinIndicatorTextViews) {
                if (!activePinIndicatorTextViews.containsKey(
                        subtitleView
                )) {
                    originalSubtitleStartDrawables.put(
                            subtitleView,
                            current[0]
                    );
                    originalSubtitleDrawablePaddings.put(
                            subtitleView,
                            subtitleView.getCompoundDrawablePadding()
                    );
                }

                activePinIndicatorTextViews.put(
                        subtitleView,
                        Boolean.TRUE
                );
            }

            int iconSize = Math.max(
                    dpToPx(
                            subtitleView.getContext(),
                            16
                    ),
                    Math.round(
                            subtitleView.getTextSize()
                                    * 1.05f
                    )
            );

            PinIndicatorDrawable pinDrawable =
                    new PinIndicatorDrawable(
                            subtitleView.getCurrentTextColor(),
                            iconSize
                    );

            pinDrawable.setBounds(
                    0,
                    0,
                    iconSize,
                    iconSize
            );

            subtitleView.setCompoundDrawablesRelative(
                    pinDrawable,
                    current[1],
                    current[2],
                    current[3]
            );

            subtitleView.setCompoundDrawablePadding(
                    dpToPx(
                            subtitleView.getContext(),
                            2
                    )
            );
        } else {
            restoreSubtitleStartDrawable(
                    subtitleView,
                    current
            );
        }

        subtitleView.requestLayout();
        subtitleView.invalidate();
        itemView.invalidate();

        Log.d(TAG, "DirectFlyoutPinIndicatorRefresh"
                + " pass=" + pass
                + " applied=true"
                + " pinned=" + pinned
                + " playlistId=" + playlistId
                + " title="
                + textValue(rowTextViews.title)
                + " subtitle="
                + textValue(subtitleView));

        return true;
    }

    private static void postDirectFlyoutRowPinIndicatorRefresh(
            @Nullable View itemView,
            boolean pinned,
            @Nullable String playlistId
    ) {
        if (itemView == null) {
            Log.d(TAG, "DirectFlyoutPinIndicatorRefresh"
                    + " pass=scheduled"
                    + " applied=false"
                    + " reason=noCapturedRow"
                    + " playlistId=" + playlistId);
            return;
        }

        final String originalRowText =
                collectRowText(itemView);

        mainHandler.post(
                () -> applyDirectFlyoutRowPinIndicator(
                        itemView,
                        pinned,
                        playlistId,
                        "immediate"
                )
        );

        mainHandler.postDelayed(
                () -> {
                    if (!itemView.isAttachedToWindow()
                            || !originalRowText.equals(
                            collectRowText(itemView)
                    )) {
                        Log.d(TAG,
                                "DirectFlyoutPinIndicatorRefresh"
                                        + " pass=afterLayout"
                                        + " applied=false"
                                        + " reason=rowChanged"
                                        + " playlistId="
                                        + playlistId);
                        return;
                    }

                    applyDirectFlyoutRowPinIndicator(
                            itemView,
                            pinned,
                            playlistId,
                            "afterLayout"
                    );
                },
                48L
        );

        mainHandler.postDelayed(
                () -> {
                    if (!itemView.isAttachedToWindow()
                            || !originalRowText.equals(
                            collectRowText(itemView)
                    )) {
                        Log.d(TAG,
                                "DirectFlyoutPinIndicatorRefresh"
                                        + " pass=settled"
                                        + " applied=false"
                                        + " reason=rowChanged"
                                        + " playlistId="
                                        + playlistId);
                        return;
                    }

                    applyDirectFlyoutRowPinIndicator(
                            itemView,
                            pinned,
                            playlistId,
                            "settled"
                    );
                },
                160L
        );
    }

    private static void installAdapterPinIndicatorMetadata(
            @Nullable Object owner,
            @Nullable Object visualAdapter
    ) {
        if (owner == null || visualAdapter == null) return;

        Map<Integer, String> visualPlaylistIds;
        Set<Integer> pinnedVisualPositions;

        synchronized (ownerVisualPlaylistIds) {
            Map<Integer, String> storedIds =
                    ownerVisualPlaylistIds.get(owner);
            Set<Integer> storedPinned =
                    ownerPinnedVisualPositions.get(owner);

            if (storedIds == null || storedPinned == null) {
                return;
            }

            visualPlaylistIds =
                    new LinkedHashMap<>(storedIds);
            pinnedVisualPositions =
                    new LinkedHashSet<>(storedPinned);
        }

        synchronized (adapterVisualPlaylistIds) {
            adapterVisualPlaylistIds.clear();
            adapterPinnedVisualPositions.clear();

            adapterVisualPlaylistIds.put(
                    visualAdapter,
                    visualPlaylistIds
            );
            adapterPinnedVisualPositions.put(
                    visualAdapter,
                    pinnedVisualPositions
            );
        }

        Log.d(TAG, "LibraryPinIndicatorMetadata"
                + " adapterIdentity="
                + identityString(visualAdapter)
                + " playlistRows="
                + visualPlaylistIds.size()
                + " pinnedPositions="
                + pinnedVisualPositions);
    }

    private static void applyBoundRowPinIndicator(
            @Nullable Object adapter,
            @Nullable View itemView,
            int visualPosition
    ) {
        if (adapter == null || itemView == null) return;

        boolean isPlaylist;
        boolean isPinned;
        String playlistId;

        synchronized (adapterVisualPlaylistIds) {
            Map<Integer, String> playlistIds =
                    adapterVisualPlaylistIds.get(adapter);
            Set<Integer> pinnedPositions =
                    adapterPinnedVisualPositions.get(adapter);

            if (playlistIds == null || pinnedPositions == null) {
                return;
            }

            playlistId = playlistIds.get(visualPosition);
            isPlaylist = playlistId != null;
            isPinned = pinnedPositions.contains(
                    visualPosition
            );
        }

        RowTextViews rowTextViews =
                findRowTextViews(itemView);
        if (rowTextViews == null
                || rowTextViews.subtitle == null) {
            return;
        }

        /*
         * Remove the legacy title prefix if a row survives an in-process
         * update, then place a native-style vector pin beside the subtitle.
         * A recycled playlist holder can later represent a fixed/non-playlist
         * row, so the non-playlist path must restore any prior pin drawable.
         */
        stripLegacyPinPrefix(rowTextViews.title);
        stripLegacyPinPrefix(rowTextViews.subtitle);

        TextView subtitleView = rowTextViews.subtitle;
        Drawable[] current =
                subtitleView.getCompoundDrawablesRelative();

        if (isPlaylist && isPinned) {
            synchronized (activePinIndicatorTextViews) {
                if (!activePinIndicatorTextViews.containsKey(
                        subtitleView
                )) {
                    originalSubtitleStartDrawables.put(
                            subtitleView,
                            current[0]
                    );
                    originalSubtitleDrawablePaddings.put(
                            subtitleView,
                            subtitleView.getCompoundDrawablePadding()
                    );
                }

                activePinIndicatorTextViews.put(
                        subtitleView,
                        Boolean.TRUE
                );
            }

            int iconSize = Math.max(
                    dpToPx(subtitleView.getContext(), 16),
                    Math.round(subtitleView.getTextSize() * 1.05f)
            );

            PinIndicatorDrawable pinDrawable =
                    new PinIndicatorDrawable(
                            subtitleView.getCurrentTextColor(),
                            iconSize
                    );
            pinDrawable.setBounds(
                    0,
                    0,
                    iconSize,
                    iconSize
            );

            subtitleView.setCompoundDrawablesRelative(
                    pinDrawable,
                    current[1],
                    current[2],
                    current[3]
            );
            subtitleView.setCompoundDrawablePadding(
                    dpToPx(subtitleView.getContext(), 2)
            );
        } else {
            restoreSubtitleStartDrawable(
                    subtitleView,
                    current
            );
        }

        if (isPlaylist && rowPinIndicatorLogCount < 40) {
            rowPinIndicatorLogCount++;

            Log.d(TAG, "LibraryPinIndicator"
                    + " visualPosition="
                    + visualPosition
                    + " pinned=" + isPinned
                    + " playlistId=" + playlistId
                    + " title="
                    + textValue(rowTextViews.title)
                    + " subtitle="
                    + textValue(subtitleView)
                    + " mode=subtitleVectorPin");
        }
    }

    @Nullable
    private static RowTextViews findRowTextViews(
            @Nullable View root
    ) {
        if (root == null) return null;

        ArrayList<TextView> candidates =
                new ArrayList<>();
        collectVisibleTextViews(
                root,
                candidates,
                0
        );

        if (candidates.isEmpty()) return null;

        TextView title = null;
        int titleIndex = -1;
        float largestTextSize = -1.0f;

        for (int index = 0;
             index < candidates.size();
             index++) {
            TextView candidate = candidates.get(index);
            CharSequence text = candidate.getText();

            if (text == null
                    || text.toString().trim().isEmpty()) {
                continue;
            }

            float textSize = candidate.getTextSize();

            if (title == null
                    || textSize > largestTextSize) {
                title = candidate;
                titleIndex = index;
                largestTextSize = textSize;
            }
        }

        if (title == null) return null;

        TextView subtitle = null;

        /*
         * Library rows traverse title then subtitle. Prefer the first nonempty
         * text view after the title whose type size does not exceed the title.
         */
        for (int index = titleIndex + 1;
             index < candidates.size();
             index++) {
            TextView candidate = candidates.get(index);
            CharSequence text = candidate.getText();

            if (text == null
                    || text.toString().trim().isEmpty()
                    || candidate.getTextSize()
                    > largestTextSize + 0.5f) {
                continue;
            }

            subtitle = candidate;
            break;
        }

        if (subtitle == null) {
            float bestSize = Float.MAX_VALUE;

            for (TextView candidate : candidates) {
                if (candidate == title) continue;

                CharSequence text = candidate.getText();
                if (text == null
                        || text.toString().trim().isEmpty()) {
                    continue;
                }

                float textSize = candidate.getTextSize();
                if (textSize < bestSize) {
                    subtitle = candidate;
                    bestSize = textSize;
                }
            }
        }

        return subtitle == null
                ? null
                : new RowTextViews(title, subtitle);
    }

    private static void collectVisibleTextViews(
            @Nullable View view,
            List<TextView> result,
            int depth
    ) {
        if (view == null
                || depth > 8
                || result.size() >= 16
                || view.getVisibility() != View.VISIBLE) {
            return;
        }

        if (view instanceof TextView) {
            result.add((TextView) view);
        }

        if (!(view instanceof ViewGroup)) return;

        ViewGroup group = (ViewGroup) view;
        int count = Math.min(
                group.getChildCount(),
                30
        );

        for (int index = 0;
             index < count;
             index++) {
            collectVisibleTextViews(
                    group.getChildAt(index),
                    result,
                    depth + 1
            );
        }
    }

    private static void stripLegacyPinPrefix(
            @Nullable TextView textView
    ) {
        if (textView == null) return;

        CharSequence text = textView.getText();
        if (text == null) return;

        String current = text.toString();
        if (current.startsWith(LEGACY_ROW_PIN_PREFIX)) {
            textView.setText(
                    current.substring(
                            LEGACY_ROW_PIN_PREFIX.length()
                    )
            );
        }
    }

    private static String textValue(
            @Nullable TextView textView
    ) {
        if (textView == null || textView.getText() == null) {
            return "null";
        }

        return textView.getText().toString();
    }

    private static int dpToPx(
            Context context,
            int dp
    ) {
        if (context == null) return dp;

        return Math.max(
                1,
                Math.round(
                        dp * context.getResources()
                                .getDisplayMetrics().density
                )
        );
    }

    private static void restoreSubtitleStartDrawable(
            TextView subtitleView,
            Drawable[] current
    ) {
        Drawable originalStart;
        Integer originalPadding;

        synchronized (activePinIndicatorTextViews) {
            if (!activePinIndicatorTextViews.containsKey(
                    subtitleView
            )) {
                return;
            }

            activePinIndicatorTextViews.remove(
                    subtitleView
            );
            originalStart =
                    originalSubtitleStartDrawables.remove(
                            subtitleView
                    );
            originalPadding =
                    originalSubtitleDrawablePaddings.remove(
                            subtitleView
                    );
        }

        subtitleView.setCompoundDrawablesRelative(
                originalStart,
                current[1],
                current[2],
                current[3]
        );

        if (originalPadding != null) {
            subtitleView.setCompoundDrawablePadding(
                    originalPadding
            );
        }
    }

    private static void clearAllVisiblePinIndicators() {
        ArrayList<TextView> markedViews;

        synchronized (activePinIndicatorTextViews) {
            markedViews =
                    new ArrayList<>(
                            activePinIndicatorTextViews.keySet()
                    );
        }

        for (TextView textView : markedViews) {
            if (textView == null) continue;

            Drawable[] current =
                    textView.getCompoundDrawablesRelative();
            restoreSubtitleStartDrawable(
                    textView,
                    current
            );
            stripLegacyPinPrefix(textView);
        }
    }

    private static final class RowTextViews {
        private final TextView title;
        private final TextView subtitle;

        private RowTextViews(
                TextView title,
                TextView subtitle
        ) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /**
     * Small resource-independent vector traced from the supplied native-looking push-pin silhouette
     * in the requested mockup. It inherits the subtitle's current text color,
     * so it automatically follows dark/light theme and disabled-state tint.
     */
    private static final class PinIndicatorDrawable
            extends Drawable {
        private final Paint paint =
                new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int intrinsicSize;

        private PinIndicatorDrawable(
                int color,
                int intrinsicSize
        ) {
            this.intrinsicSize = intrinsicSize;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            if (canvas == null || getBounds().isEmpty()) {
                return;
            }

            int save = canvas.save();
            canvas.translate(
                    getBounds().left,
                    getBounds().top
            );
            canvas.scale(
                    getBounds().width() / 24.0f,
                    getBounds().height() / 24.0f
            );
            canvas.translate(-1.75f, 0.0f);
            canvas.rotate(26.0f, 12.0f, 12.0f);

            /*
             * Silhouette traced from the supplied native-looking pin:
             * wide rounded cap, tapered body, stepped shoulders, and a
             * longer narrow point. The path is kept upright here and the
             * requested 24-degree clockwise tilt is applied above.
             */
            path.reset();
            path.moveTo(7.22f, 1.00f);
            path.lineTo(6.72f, 2.09f);
            path.lineTo(7.09f, 2.61f);
            path.lineTo(7.98f, 2.83f);
            path.lineTo(8.13f, 4.07f);
            path.lineTo(7.67f, 8.87f);
            path.lineTo(6.87f, 10.00f);
            path.lineTo(5.96f, 10.72f);
            path.lineTo(5.48f, 12.26f);
            path.lineTo(5.46f, 13.54f);
            path.lineTo(6.30f, 14.15f);
            path.lineTo(9.41f, 14.41f);
            path.lineTo(10.26f, 14.80f);
            path.lineTo(9.63f, 21.04f);
            path.lineTo(10.11f, 22.70f);
            path.lineTo(10.52f, 23.00f);
            path.lineTo(11.04f, 22.63f);
            path.lineTo(11.78f, 21.35f);
            path.lineTo(12.30f, 15.80f);
            path.lineTo(12.70f, 14.83f);
            path.lineTo(16.85f, 15.30f);
            path.lineTo(17.39f, 14.76f);
            path.lineTo(17.70f, 12.24f);
            path.lineTo(16.28f, 10.17f);
            path.lineTo(16.89f, 4.00f);
            path.lineTo(18.22f, 3.65f);
            path.lineTo(18.59f, 2.65f);
            path.lineTo(18.00f, 2.17f);
            path.close();

            canvas.drawPath(path, paint);
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(
                @Nullable ColorFilter colorFilter
        ) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return intrinsicSize;
        }

        @Override
        public int getIntrinsicHeight() {
            return intrinsicSize;
        }
    }

    private static boolean hasActiveFactoryMapForOwner(
            @Nullable Object owner,
            int expectedSize
    ) {
        if (owner == null || expectedSize <= 0) return false;

        Object controller = readFieldByName(owner, "o");
        Object visualAdapter = controller == null
                ? null
                : readFieldByName(controller, "f");

        return visualAdapter != null
                && hasActiveAdapterPositionMap(
                        visualAdapter,
                        expectedSize
                );
    }

    private static boolean refreshActiveFactoryPositionMap(
            @Nullable Context context
    ) {
        Object owner = activeAdapterProxyFactoryOwner;
        Object visualAdapter =
                activeAdapterProxyFactoryVisualAdapter;

        if (owner == null
                || visualAdapter == null
                || context == null) {
            Log.d(TAG, "PinTogglePositionMapRefreshSkipped"
                    + " reason=missingActiveContext"
                    + " owner=" + identityString(owner)
                    + " visualAdapter="
                    + identityString(visualAdapter)
                    + " contextAvailable="
                    + (context != null));
            return false;
        }

        Object sourceAdapter = readFieldByName(owner, "p");
        Integer sourceCount =
                invokeIntNoArg(sourceAdapter, "a");

        if (sourceAdapter == null
                || sourceCount == null
                || sourceCount < 3
                || sourceCount > 24) {
            Log.d(TAG, "PinTogglePositionMapRefreshSkipped"
                    + " reason=invalidSource"
                    + " sourceAdapterType="
                    + objectTypeName(sourceAdapter)
                    + " sourceCount=" + sourceCount);
            return false;
        }

        int[] visualToSource =
                buildAdapterProxyFactoryPositionMap(
                        owner,
                        sourceAdapter,
                        sourceCount
                );

        if (visualToSource == null) {
            Log.d(TAG, "PinTogglePositionMapRefreshSkipped"
                    + " reason=mapUnavailable"
                    + " sourceCount=" + sourceCount);
            return false;
        }

        synchronized (adapterProxyFactoryVisualToSource) {
            adapterProxyFactoryVisualToSource.put(
                    owner,
                    visualToSource
            );
            adapterProxyFactorySourceCounts.put(
                    owner,
                    sourceCount
            );
        }

        synchronized (adapterVisualToSourcePositions) {
            adapterVisualToSourcePositions.clear();
            adapterVisualToSourcePositions.put(
                    visualAdapter,
                    visualToSource.clone()
            );
        }

        installAdapterPinIndicatorMetadata(
                owner,
                visualAdapter
        );

        activeLibraryAdapter = visualAdapter;

        clearAllVisiblePinIndicators();

        boolean notified =
                invokeNoArgVoid(visualAdapter, "fq");

        postVisiblePinIndicatorRefresh(
                visualAdapter
        );

        Log.d(TAG, "PinTogglePositionMapRefreshed"
                + " adapterIdentity="
                + identityString(visualAdapter)
                + " sourceCount=" + sourceCount
                + " fullNotify=" + notified
                + " visualToSource="
                + java.util.Arrays.toString(
                visualToSource
        ));

        return true;
    }

    private static boolean hasActiveAdapterPositionMap(
            @Nullable Object adapter,
            int expectedSize
    ) {
        if (adapter == null || expectedSize <= 0) {
            return false;
        }

        synchronized (adapterVisualToSourcePositions) {
            int[] visualToSource =
                    adapterVisualToSourcePositions.get(adapter);

            return visualToSource != null
                    && visualToSource.length == expectedSize;
        }
    }

    private static void beginAdapterPositionRemap(
            @Nullable Object adapter,
            String kind
    ) {
        if (!isFeatureEnabled()) {
            pendingAdapterPositionRemapTarget.remove();
            pendingAdapterPositionRemapKind.remove();
            return;
        }

        pendingAdapterPositionRemapTarget.set(adapter);
        pendingAdapterPositionRemapKind.set(kind);
    }

    public static void beginAdapterViewTypePositionRemap(
            @Nullable Object adapter
    ) {
        beginAdapterPositionRemap(adapter, "viewType");
    }

    public static void beginAdapterStableIdPositionRemap(
            @Nullable Object adapter
    ) {
        beginAdapterPositionRemap(adapter, "stableId");
    }

    public static void beginAdapterBindPositionRemap(
            @Nullable Object adapter
    ) {
        beginAdapterPositionRemap(adapter, "bind");
    }

    public static int remapAdapterPosition(int visualPosition) {
        if (!isFeatureEnabled()) {
            pendingAdapterPositionRemapTarget.remove();
            pendingAdapterPositionRemapKind.remove();
            return visualPosition;
        }

        Object adapter =
                pendingAdapterPositionRemapTarget.get();
        pendingAdapterPositionRemapKind.get();

        pendingAdapterPositionRemapTarget.remove();
        pendingAdapterPositionRemapKind.remove();

        if (adapter == null || visualPosition < 0) {
            return visualPosition;
        }

        int sourcePosition = visualPosition;

        synchronized (adapterVisualToSourcePositions) {
            int[] visualToSource =
                    adapterVisualToSourcePositions.get(adapter);

            if (visualToSource != null
                    && visualPosition
                    < visualToSource.length) {
                sourcePosition =
                        visualToSource[visualPosition];
            }
        }

        return sourcePosition;
    }








    private static List<AdapterProxySource>
    findLatestAdapterProxySourceBatch(
            Object owner,
            int expectedSize
    ) {
        if (owner == null || expectedSize <= 0) {
            return Collections.emptyList();
        }

        synchronized (adapterProxySourceHistory) {
            ArrayList<AdapterProxySource> history =
                    adapterProxySourceHistory.get(owner);

            if (history == null
                    || history.size() < expectedSize) {
                return Collections.emptyList();
            }

            long now = SystemClock.uptimeMillis();

            /*
             * Search newest-first for one contiguous set of source indices.
             * Full Library rebuilds call h(0), h(1), ... in a loop, but allow
             * a non-zero base for incremental insert/replace operations.
             */
            for (int end = history.size();
                 end >= expectedSize;
                 end--) {
                int start = end - expectedSize;
                int minIndex = Integer.MAX_VALUE;
                int maxIndex = Integer.MIN_VALUE;
                Set<Integer> seen = new LinkedHashSet<>();
                boolean valid = true;

                for (int index = start; index < end; index++) {
                    AdapterProxySource source =
                            history.get(index);

                    if (source == null
                            || source.owner != owner
                            || source.sourceObject == null
                            || now - source.completedAtMs > 5000L
                            || !seen.add(source.sourceIndex)) {
                        valid = false;
                        break;
                    }

                    minIndex = Math.min(
                            minIndex,
                            source.sourceIndex
                    );
                    maxIndex = Math.max(
                            maxIndex,
                            source.sourceIndex
                    );
                }

                if (!valid
                        || seen.size() != expectedSize
                        || maxIndex - minIndex + 1
                        != expectedSize) {
                    continue;
                }

                ArrayList<AdapterProxySource> ordered =
                        new ArrayList<>(
                                Collections.nCopies(
                                        expectedSize,
                                        (AdapterProxySource) null
                                )
                        );

                for (int index = start; index < end; index++) {
                    AdapterProxySource source =
                            history.get(index);
                    int position =
                            source.sourceIndex - minIndex;

                    if (position < 0
                            || position >= expectedSize
                            || ordered.get(position) != null) {
                        valid = false;
                        break;
                    }

                    ordered.set(position, source);
                }

                if (!valid || ordered.contains(null)) {
                    continue;
                }

                List<Integer> sourceIndices =
                        new ArrayList<>();
                List<String> sourceTypes =
                        new ArrayList<>();
                List<String> sourceIdentities =
                        new ArrayList<>();
                List<String> renderInfoIdentities =
                        new ArrayList<>();

                for (AdapterProxySource source : ordered) {
                    sourceIndices.add(source.sourceIndex);
                    sourceTypes.add(
                            objectTypeName(source.sourceObject)
                    );
                    sourceIdentities.add(
                            identityString(source.sourceObject)
                    );
                    renderInfoIdentities.add(
                            identityString(source.renderInfo)
                    );
                }

                Log.d(TAG, "PreSubmitOrderedSourceBatch"
                        + " size=" + expectedSize
                        + " sourceIndices=" + sourceIndices
                        + " sourceTypes=" + sourceTypes
                        + " sourceIdentities="
                        + sourceIdentities
                        + " renderInfoIdentities="
                        + renderInfoIdentities);

                history.subList(0, end).clear();

                return ordered;
            }
        }

        return Collections.emptyList();
    }

    @Nullable
    private static AdapterProxyMapping
    chooseAdapterProxyDirectSourceMapping(
            List<AdapterProxySource> orderedSources,
            List<?> renderInfos,
            Context context,
            List<String> pinOrder
    ) {
        int expected = lastKnownLibraryPlaylistCount;

        LinkedHashMap<String, String> signatureToId =
                new LinkedHashMap<>();

        synchronized (playlistIdByRowSignature) {
            signatureToId.putAll(playlistIdByRowSignature);
        }

        Map<String, String> persisted =
                PinStore.getPlaylistSignatures(context);

        for (Map.Entry<String, String> entry : persisted.entrySet()) {
            String playlistId = entry.getKey();
            String signature = entry.getValue();

            if (isPersistentPlaylistId(playlistId)
                    && signature != null
                    && !signature.isEmpty()) {
                signatureToId.put(signature, playlistId);
            }
        }

        LinkedHashMap<Integer, String> mapped =
                new LinkedHashMap<>();
        Set<String> distinctIds = new LinkedHashSet<>();
        boolean ambiguous = false;
        int capturedSourceObjects = 0;
        int canonicalMatches = 0;
        int signatureMatches = 0;

        for (int position = 0;
             position < renderInfos.size()
                     && position < orderedSources.size();
             position++) {
            AdapterProxySource source =
                    orderedSources.get(position);

            if (source == null
                    || source.sourceObject == null) {
                continue;
            }

            capturedSourceObjects++;

            Set<String> canonicalIds =
                    collectCanonicalPlaylistIds(
                            source.sourceObject,
                            10
                    );

                LinkedHashSet<String> persistentIds =
                        new LinkedHashSet<>();

                for (String candidate : canonicalIds) {
                    if (isPersistentPlaylistId(candidate)) {
                        persistentIds.add(candidate);
                    }
                }

                String playlistId = null;
                String identitySource = null;
                String matchedSignature = null;
                Set<String> sourceStrings =
                        Collections.emptySet();

                Set<String> knownPlaylistIds =
                        new LinkedHashSet<>();
                knownPlaylistIds.addAll(pinOrder);
                knownPlaylistIds.addAll(
                        signatureToId.values()
                );

                playlistId =
                        resolveCanonicalPlaylistId(
                                persistentIds,
                                pinOrder,
                                knownPlaylistIds
                        );

                if (playlistId != null) {
                    identitySource =
                            persistentIds.size() == 1
                                    ? "canonical"
                                    : "canonicalNormalized";
                    canonicalMatches++;
                } else if (persistentIds.size() > 1) {
                    ambiguous = true;
                    identitySource = "multipleCanonical";
                } else if (!signatureToId.isEmpty()) {
                    sourceStrings =
                            collectObjectStrings(
                                    source.sourceObject
                            );

                    matchedSignature =
                            findUniqueKnownRowSignature(
                                    sourceStrings,
                                    signatureToId
                            );

                    if (matchedSignature != null) {
                        playlistId =
                                signatureToId.get(
                                        matchedSignature
                                );
                        identitySource = "signature";
                        signatureMatches++;
                    }
                }

                if (playlistId != null) {
                    if (!distinctIds.add(playlistId)) {
                        ambiguous = true;
                    } else {
                        mapped.put(position, playlistId);
                    }
                }

                if (directSourceRowLogCount < 100) {
                    directSourceRowLogCount++;

                    List<String> sample = new ArrayList<>();
                    for (String value : sourceStrings) {
                        sample.add(
                                abbreviateDiagnosticValue(value)
                        );
                        if (sample.size() >= 8) break;
                    }

                    Log.d(TAG, "PreSubmitDirectSourceRow"
                            + " position=" + position
                            + " sourceIndex="
                            + source.sourceIndex
                            + " sourceType="
                            + objectTypeName(
                            source.sourceObject
                    )
                            + " playlistId=" + playlistId
                            + " identitySource="
                            + identitySource
                            + " canonicalIds="
                            + persistentIds
                            + " matchedSignature="
                            + matchedSignature
                            + " strings=" + sample);
                }
            }

        Set<String> pinnedPresent = new LinkedHashSet<>();
        for (String pinnedId : pinOrder) {
            if (distinctIds.contains(pinnedId)) {
                pinnedPresent.add(pinnedId);
            }
        }

        /*
         * A canonical mapping can safely work on the first load. A
         * signature-only mapping waits until the normal bind path has learned
         * the exact number of playlist rows.
         */
        int requiredPlaylistRows =
                expected >= 3
                        ? expected
                        : mapped.size();

        boolean enoughForFirstLoad =
                expected < 3
                        && mapped.size() >= 3
                        && !pinnedPresent.isEmpty();

        boolean completeKnownLibrary =
                expected >= 3
                        && mapped.size() == expected
                        && distinctIds.size() == expected
                        && !pinnedPresent.isEmpty();

        Log.d(TAG, "PreSubmitDirectSourceSummary"
                + " total=" + renderInfos.size()
                + " capturedSourceObjects="
                + capturedSourceObjects
                + " expected=" + expected
                + " required=" + requiredPlaylistRows
                + " mapped=" + mapped.size()
                + " canonicalMatches="
                + canonicalMatches
                + " signatureMatches="
                + signatureMatches
                + " ambiguous=" + ambiguous
                + " pinnedPresent=" + pinnedPresent);

        if (ambiguous
                || (!enoughForFirstLoad
                && !completeKnownLibrary)) {
            Log.d(TAG, "PreSubmitDirectSourceSkipped"
                    + " expected=" + expected
                    + " mapped=" + mapped.size()
                    + " distinct=" + distinctIds.size()
                    + " ambiguous=" + ambiguous
                    + " pinnedPresent=" + pinnedPresent);
            return null;
        }

        int score =
                (mapped.size() * 100)
                        + (canonicalMatches * 50)
                        + (pinnedPresent.size() * 100);

        return new AdapterProxyMapping(
                "directSourceObject",
                0,
                score,
                mapped,
                false
        );
    }

    @Nullable
    private static String resolveCanonicalPlaylistId(
            Set<String> rawCandidates,
            List<String> pinOrder,
            Set<String> knownPlaylistIds
    ) {
        if (rawCandidates == null
                || rawCandidates.isEmpty()) {
            return null;
        }

        LinkedHashSet<String> normalized =
                new LinkedHashSet<>();

        for (String raw : rawCandidates) {
            if (raw == null || raw.isEmpty()) continue;

            String candidate = raw;

            /*
             * The protobuf byte scan can include the field's leading "PL"
             * twice (PLPL...) or append the following field tag ("2").
             * Collapse the duplicated prefix first. The remaining prefix
             * relationship is resolved below.
             */
            while (candidate.startsWith("PLPL")
                    && candidate.length() > 4) {
                candidate = candidate.substring(2);
            }

            if (isPersistentPlaylistId(candidate)) {
                normalized.add(candidate);
            }
        }

        if (normalized.isEmpty()) return null;

        if (pinOrder != null) {
            for (String pinnedId : pinOrder) {
                if (normalized.contains(pinnedId)) {
                    return pinnedId;
                }
            }
        }

        if (knownPlaylistIds != null) {
            for (String knownId : knownPlaylistIds) {
                if (normalized.contains(knownId)) {
                    return knownId;
                }
            }
        }

        if (normalized.size() == 1) {
            return normalized.iterator().next();
        }

        List<String> byLength =
                new ArrayList<>(normalized);
        Collections.sort(
                byLength,
                (left, right) -> {
                    int lengthCompare =
                            Integer.compare(
                                    left.length(),
                                    right.length()
                            );
                    return lengthCompare != 0
                            ? lengthCompare
                            : left.compareTo(right);
                }
        );

        for (String candidate : byLength) {
            boolean commonPrefix = true;

            for (String other : normalized) {
                if (!other.equals(candidate)
                        && !other.startsWith(candidate)) {
                    commonPrefix = false;
                    break;
                }
            }

            if (commonPrefix) {
                return candidate;
            }
        }

        return null;
    }

    @Nullable
    private static String findUniqueKnownRowSignature(
            Set<String> itemStrings,
            Map<String, String> knownSignatures
    ) {
        if (itemStrings == null
                || itemStrings.isEmpty()
                || knownSignatures == null
                || knownSignatures.isEmpty()) {
            return null;
        }

        Set<String> normalizedStrings = new LinkedHashSet<>();
        for (String value : itemStrings) {
            String normalized = normalizeRowIdentityText(value);
            if (!normalized.isEmpty()) {
                normalizedStrings.add(normalized);
            }
        }

        String match = null;

        for (String signature : knownSignatures.keySet()) {
            int separator = signature.indexOf('\u001f');
            String title = separator >= 0
                    ? signature.substring(0, separator)
                    : signature;
            String subtitle = separator >= 0
                    ? signature.substring(separator + 1)
                    : "";

            if (!normalizedStrings.contains(title)) continue;
            if (!subtitle.isEmpty()
                    && !normalizedStrings.contains(subtitle)) {
                continue;
            }

            if (match != null && !match.equals(signature)) {
                return null;
            }

            match = signature;
        }

        return match;
    }






    private static String identityString(@Nullable Object value) {
        if (value == null) return "null";
        return Integer.toHexString(System.identityHashCode(value));
    }




    public static void beginBoundLibraryRow(
            @Nullable Object adapter,
            @Nullable Object holder,
            int position
    ) {
        if (!deferredBindHookLogged) {
            deferredBindHookLogged = true;
            Log.d(TAG, "DiagnosticBuild=" + BUILD_ID
                    + " bindCompletionHook=true"
                    + " adapterType=" + objectTypeName(adapter)
                    + " holderType=" + objectTypeName(holder));
        }

        if (!isLibraryAdapter(adapter)
                || holder == null) {
            return;
        }

        if (!isFeatureEnabled()
                || !hasAnyPinsFast()) {
            return;
        }

        if (isAdapterBindCaptureSuppressed(adapter)) {
            return;
        }

        Object controller = readFieldByName(adapter, "d");
        Object listObject = readFieldByName(controller, "b");
        if (!(listObject instanceof List)) return;

        List<?> items = (List<?>) listObject;
        if (position < 0 || position >= items.size()) return;

        Object item = items.get(position);
        Object stableIdObject = readFieldByName(item, "b");
        if (!(stableIdObject instanceof Number)) return;

        Object itemViewObject = readFieldByName(holder, "a");
        if (!(itemViewObject instanceof View)) return;

        PendingBoundRow pending = new PendingBoundRow(
                adapter,
                holder,
                position,
                ((Number) stableIdObject).longValue(),
                (View) itemViewObject
        );

        synchronized (pendingBoundRows) {
            pendingBoundRows.put(holder, pending);
            pendingBoundRows.put(pending.itemView, pending);

            if (pendingBoundRows.size() > 512) {
                pendingBoundRows.clear();
                pendingBoundRows.put(holder, pending);
                pendingBoundRows.put(pending.itemView, pending);
            }
        }

    }

    public static void finishBoundLibraryRow(
            @Nullable Object holderOrView
    ) {
        if (holderOrView == null
                || !isFeatureEnabled()
                || !hasAnyPinsFast()) {
            return;
        }

        PendingBoundRow pending;
        synchronized (pendingBoundRows) {
            pending = pendingBoundRows.remove(holderOrView);
            if (pending == null) return;

            pendingBoundRows.remove(pending.holder);
            pendingBoundRows.remove(pending.itemView);
        }

        rememberVisibleBoundRow(
                pending.adapter,
                pending.position,
                pending.itemView
        );

        applyBoundRowPinIndicator(
                pending.adapter,
                pending.itemView,
                pending.position
        );

        captureBoundLibraryRow(
                pending.adapter,
                pending.holder,
                pending.position,
                pending.stableId
        );
    }

    private static void captureBoundLibraryRow(
            Object adapter,
            Object holder,
            int scheduledPosition,
            long stableId
    ) {
        if (isAdapterBindCaptureSuppressed(adapter)) {
            return;
        }

        Object controller = readFieldByName(adapter, "d");
        Object listObject = readFieldByName(controller, "b");
        if (!(listObject instanceof List)) return;

        List<?> items = (List<?>) listObject;

        if (hasActiveAdapterPositionMap(
                adapter,
                items.size()
        )) {
            return;
        }

        Object itemViewObject = readFieldByName(holder, "a");
        if (!(itemViewObject instanceof View)) return;

        View itemView = (View) itemViewObject;
        List<String> rowTexts = collectRowTextValues(itemView);
        String rowText = rowTexts.isEmpty()
                ? "[]"
                : rowTexts.toString();
        String rowSignature =
                buildRowIdentitySignature(rowTexts);

        Set<String> ids = collectPlaylistIdsFromBoundView(itemView);
        String candidatePlaylistId = findBestPlaylistId(ids);
        String playlistId = resolveBoundPlaylistId(
                rowSignature,
                candidatePlaylistId
        );

        rememberAdapterNativeOrder(adapter, items);

        if (playlistId == null) {
            if (boundRowNoIdLogCount < 40
                    && items.size() >= 10) {
                boundRowNoIdLogCount++;
                Log.d(TAG, "BoundLibraryRowNoId"
                        + " scheduledPosition=" + scheduledPosition
                        + " total=" + items.size()
                        + " stableRowId=" + stableId
                        + " candidateId=" + candidatePlaylistId
                        + " rowSignature=" + rowSignature
                        + " rowText=" + rowText);
            }
            return;
        }

        playlistId = rememberBoundAdapterPlaylistId(
                adapter,
                stableId,
                playlistId
        );

        if (boundRowLogCount < 120) {
            boundRowLogCount++;
            Log.d(TAG, "BoundLibraryRow"
                    + " scheduledPosition=" + scheduledPosition
                    + " total=" + items.size()
                    + " stableRowId=" + stableId
                    + " playlistId=" + playlistId
                    + " candidateId=" + candidatePlaylistId
                    + " identitySource="
                    + (playlistId.equals(candidatePlaylistId)
                    ? "byhm"
                    : "rowSignature")
                    + " rowSignature=" + rowSignature
                    + " rowText=" + rowText);
        }

        Context context = itemView.getContext();

        if (context != null
                && rowSignature != null
                && !rowSignature.isEmpty()) {
            PinStore.setPlaylistSignature(
                    context,
                    playlistId,
                    rowSignature
            );
        }

        if (context != null
                && items.size() >= 10
                && !PinStore.getPinnedIds(context).isEmpty()) {
            if (hasActiveAdapterPositionMap(
                    adapter,
                    items.size()
            )) {
                if (adapterProxyFactoryInstallLogCount < 80) {
                    adapterProxyFactoryInstallLogCount++;

                    Log.d(TAG, "PostBindPhysicalReorderSkipped"
                            + " reason=positionMapActive"
                            + " adapterIdentity="
                            + identityString(adapter)
                            + " total=" + items.size());
                }
            } else {
                boolean mappingComplete =
                        isExpectedPlaylistMappingComplete(adapter);

                scheduleAdapterReorderAfterBinding(
                        adapter,
                        itemView,
                        mappingComplete
                );
            }
        }
    }

    @Nullable
    private static String resolveBoundPlaylistId(
            @Nullable String rowSignature,
            @Nullable String candidatePlaylistId
    ) {
        if (rowSignature == null) {
            return candidatePlaylistId;
        }

        synchronized (playlistIdByRowSignature) {
            String knownPlaylistId =
                    playlistIdByRowSignature.get(rowSignature);

            if (knownPlaylistId != null) {
                return knownPlaylistId;
            }

            if (candidatePlaylistId == null) {
                return null;
            }

            String knownSignature =
                    rowSignatureByPlaylistId.get(candidatePlaylistId);

            if (knownSignature != null
                    && !knownSignature.equals(rowSignature)) {
                if (rowIdentityConflictLogCount < 30) {
                    rowIdentityConflictLogCount++;
                    Log.d(TAG, "Rejected stale bound-row playlist id"
                            + " candidateId=" + candidatePlaylistId
                            + " knownSignature=" + knownSignature
                            + " observedSignature=" + rowSignature);
                }
                return null;
            }

            playlistIdByRowSignature.put(
                    rowSignature,
                    candidatePlaylistId
            );
            rowSignatureByPlaylistId.put(
                    candidatePlaylistId,
                    rowSignature
            );

            if (playlistIdByRowSignature.size() > 256) {
                playlistIdByRowSignature.clear();
                rowSignatureByPlaylistId.clear();

                playlistIdByRowSignature.put(
                        rowSignature,
                        candidatePlaylistId
                );
                rowSignatureByPlaylistId.put(
                        candidatePlaylistId,
                        rowSignature
                );
            }

            return candidatePlaylistId;
        }
    }

    @Nullable
    private static String buildRowIdentitySignature(
            List<String> rowTexts
    ) {
        if (rowTexts == null || rowTexts.isEmpty()) {
            return null;
        }

        String title = normalizeRowIdentityText(
                rowTexts.get(0)
        );
        if (title.isEmpty()) return null;

        String subtitle = rowTexts.size() > 1
                ? normalizeRowIdentityText(rowTexts.get(1))
                : "";

        return title + "\\u001f" + subtitle;
    }

    private static String normalizeRowIdentityText(
            @Nullable String value
    ) {
        if (value == null) return "";

        String normalized = value.trim();

        if (normalized.startsWith("Playlist • ")) {
            normalized = normalized.substring(
                    "Playlist • ".length()
            );
        }

        return normalized.replaceAll("\\\\s+", " ");
    }

    private static Set<String> collectPlaylistIdsFromBoundView(
            View root
    ) {
        Set<String> ids = new LinkedHashSet<>();
        List<View> views = new ArrayList<>();
        views.add(root);

        /*
         * Only accept IDs extracted from an actual byhm playlist-row source.
         * Generic tag/listener graphs contain page constants and unrelated
         * playlist endpoints, which caused every row to inherit one ID.
         */
        int cursor = 0;
        while (cursor < views.size() && views.size() <= 100) {
            View view = views.get(cursor++);

            collectIdsFromByhmCarrier(
                    view.getTag(),
                    ids,
                    2,
                    new IdentityHashMap<Object, Boolean>()
            );
            if (!ids.isEmpty()) return ids;

            Object keyedTags = readFieldByName(view, "mKeyedTags");
            if (keyedTags instanceof SparseArray) {
                SparseArray<?> tags = (SparseArray<?>) keyedTags;
                int count = Math.min(tags.size(), 30);

                for (int index = 0; index < count; index++) {
                    collectIdsFromByhmCarrier(
                            tags.valueAt(index),
                            ids,
                            2,
                            new IdentityHashMap<Object, Boolean>()
                    );
                    if (!ids.isEmpty()) return ids;
                }
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                int count = Math.min(group.getChildCount(), 30);

                for (int index = 0; index < count; index++) {
                    if (views.size() >= 100) break;
                    views.add(group.getChildAt(index));
                }
            }
        }

        /*
         * The playlist overflow View uses bfge.onClick, and that listener owns
         * the byhm source passed to qot.k. Inspect only bfge listener objects.
         */
        for (View view : views) {
            if (!view.isClickable()) continue;

            Object listenerInfo = readFieldByName(view, "mListenerInfo");
            if (listenerInfo == null) continue;

            for (Class<?> current = listenerInfo.getClass();
                 current != null && current != Object.class;
                 current = current.getSuperclass()) {
                Field[] fields;
                try {
                    fields = current.getDeclaredFields();
                } catch (Throwable ignored) {
                    continue;
                }

                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType().isPrimitive()) continue;

                    try {
                        field.setAccessible(true);
                        Object listener = field.get(listenerInfo);
                        if (listener == null
                                || !"bfge".equals(
                                listener.getClass().getName()
                        )) {
                            continue;
                        }

                        collectIdsFromByhmCarrier(
                                listener,
                                ids,
                                5,
                                new IdentityHashMap<Object, Boolean>()
                        );
                        if (!ids.isEmpty()) return ids;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        return ids;
    }

    private static void collectIdsFromByhmCarrier(
            @Nullable Object value,
            Set<String> output,
            int remainingDepth,
            IdentityHashMap<Object, Boolean> visited
    ) {
        if (value == null || remainingDepth < 0 || !output.isEmpty()) return;

        Class<?> type = value.getClass();

        if ("byhm".equals(type.getName())) {
            String playlistId = findBestPlaylistId(
                    collectCanonicalPlaylistIds(value, 8)
            );

            if (playlistId != null) {
                output.add(playlistId);
            }
            return;
        }

        if (isTerminalType(type)
                || type.isPrimitive()
                || value instanceof byte[]
                || isByteContainer(type)) {
            return;
        }

        if (visited.put(value, Boolean.TRUE) != null) return;

        if (type.isArray()) {
            int count = Math.min(Array.getLength(value), 20);
            for (int index = 0; index < count; index++) {
                collectIdsFromByhmCarrier(
                        Array.get(value, index),
                        output,
                        remainingDepth - 1,
                        visited
                );
                if (!output.isEmpty()) return;
            }
            return;
        }

        if (value instanceof Iterable) {
            int count = 0;
            for (Object child : (Iterable<?>) value) {
                collectIdsFromByhmCarrier(
                        child,
                        output,
                        remainingDepth - 1,
                        visited
                );
                if (!output.isEmpty() || ++count >= 20) return;
            }
            return;
        }

        if (value instanceof Map) {
            int count = 0;
            for (Object child : ((Map<?, ?>) value).values()) {
                collectIdsFromByhmCarrier(
                        child,
                        output,
                        remainingDepth - 1,
                        visited
                );
                if (!output.isEmpty() || ++count >= 20) return;
            }
            return;
        }

        for (Class<?> current = type;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType().isPrimitive()) continue;

                try {
                    field.setAccessible(true);
                    collectIdsFromByhmCarrier(
                            field.get(value),
                            output,
                            remainingDepth - 1,
                            visited
                    );
                    if (!output.isEmpty()) return;
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Nullable
    private static String rememberBoundAdapterPlaylistId(
            @Nullable Object adapter,
            long stableId,
            @Nullable String playlistId
    ) {
        if (adapter == null || playlistId == null) {
            return playlistId;
        }

        synchronized (adapterPlaylistIds) {
            Map<Long, String> mappings =
                    adapterPlaylistIds.get(adapter);

            if (mappings == null) {
                mappings = new LinkedHashMap<>();
                adapterPlaylistIds.put(adapter, mappings);
            }

            String existing = mappings.get(stableId);

            if (existing == null) {
                mappings.put(stableId, playlistId);
                return playlistId;
            }

            if (existing.equals(playlistId)) {
                return existing;
            }

            /*
             * hvg.b belongs to the backing-list item and does not change when
             * that item is moved. Recycled holder Views can temporarily show
             * text from a neighboring row after move/rebind notifications.
             * Never allow that transient View state to rewrite an established
             * stable-row identity. A new server/sort rebuild generates a new
             * stable-ID set, and rememberAdapterNativeOrder clears this map.
             */
            if (stableRowRemapConflictLogCount < 40) {
                stableRowRemapConflictLogCount++;
                Log.d(TAG, "Rejected conflicting stable-row remap"
                        + " stableRowId=" + stableId
                        + " existingId=" + existing
                        + " observedId=" + playlistId);
            }

            return existing;
        }
    }

    private static void rememberAdapterPlaylistId(
            @Nullable Object adapter,
            long stableId,
            @Nullable String playlistId
    ) {
        if (adapter == null || playlistId == null) return;

        synchronized (adapterPlaylistIds) {
            Map<Long, String> mappings =
                    adapterPlaylistIds.get(adapter);

            if (mappings == null) {
                mappings = new LinkedHashMap<>();
                adapterPlaylistIds.put(adapter, mappings);
            }

            mappings.put(stableId, playlistId);

            if (adapterPlaylistIds.size() > 30) {
                Object keep = activeLibraryAdapter;
                Map<Long, String> keepMappings =
                        keep == null ? null : adapterPlaylistIds.get(keep);

                adapterPlaylistIds.clear();

                if (keep != null && keepMappings != null) {
                    adapterPlaylistIds.put(keep, keepMappings);
                }
            }
        }
    }

    private static void rememberAdapterNativeOrder(
            Object adapter,
            List<?> items
    ) {
        List<Long> current = stableIdsForItems(items);
        if (current == null) return;

        synchronized (adapterBaseOrder) {
            List<Long> base = adapterBaseOrder.get(adapter);
            List<Long> lastApplied =
                    adapterLastAppliedOrder.get(adapter);

            boolean setChanged =
                    base == null
                            || base.size() != current.size()
                            || !new LinkedHashSet<>(base).equals(
                            new LinkedHashSet<>(current)
                    );

            boolean nativeOrderChanged =
                    lastApplied == null
                            ? base != null && !base.equals(current)
                            : !lastApplied.equals(current);

            if (setChanged || nativeOrderChanged) {
                adapterBaseOrder.put(
                        adapter,
                        new ArrayList<>(current)
                );

                if (setChanged) {
                    synchronized (adapterPlaylistIds) {
                        adapterPlaylistIds.remove(adapter);
                    }
                }

                Log.d(TAG, "Captured Library native order"
                        + " total=" + current.size()
                        + " ids=" + current);
            }
        }
    }

    @Nullable
    private static List<Long> stableIdsForItems(
            List<?> items
    ) {
        List<Long> ids = new ArrayList<>(items.size());

        for (Object item : items) {
            Object value = readFieldByName(item, "b");
            if (!(value instanceof Number)) return null;
            ids.add(((Number) value).longValue());
        }

        return ids;
    }

    private static boolean isAdapterBindCaptureSuppressed(
            Object adapter
    ) {
        long now = SystemClock.uptimeMillis();

        synchronized (adapterBindSuppressedUntilMs) {
            Long until = adapterBindSuppressedUntilMs.get(adapter);

            if (until == null) {
                return false;
            }

            if (until > now) {
                return true;
            }

            adapterBindSuppressedUntilMs.remove(adapter);
            return false;
        }
    }

    private static void suppressAdapterBindCapture(
            final Object adapter,
            long durationMs
    ) {
        synchronized (adapterBindSuppressedUntilMs) {
            adapterBindSuppressedUntilMs.put(
                    adapter,
                    SystemClock.uptimeMillis() + durationMs
            );
        }

        synchronized (adapterBindReorderGeneration) {
            adapterBindReorderGeneration.remove(adapter);
        }

        final int generation;
        synchronized (adapterSuppressionReconcileGeneration) {
            Integer previous =
                    adapterSuppressionReconcileGeneration.get(adapter);
            generation = previous == null ? 1 : previous + 1;
            adapterSuppressionReconcileGeneration.put(
                    adapter,
                    generation
            );
        }

        /*
         * A sort change can happen while notification-induced binds are being
         * suppressed. When that happens, those binds are intentionally ignored,
         * so perform one reconciliation after the suppression window. If the
         * native order changed, clear stale row mappings and force a clean bind
         * pass; normal bound-row capture then reapplies the pinned partition.
         */
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (adapterSuppressionReconcileGeneration) {
                    Integer current =
                            adapterSuppressionReconcileGeneration.get(adapter);

                    if (current == null
                            || current != generation) {
                        return;
                    }

                    adapterSuppressionReconcileGeneration.remove(adapter);
                }

                reconcileAdapterAfterSuppression(adapter);
            }
        }, durationMs + 16L);
    }

    private static void reconcileAdapterAfterSuppression(
            Object adapter
    ) {
        Object controller = readFieldByName(adapter, "d");
        Object listObject = readFieldByName(controller, "b");
        if (!(listObject instanceof List)) return;

        List<Long> currentOrder =
                stableIdsForItems((List<?>) listObject);
        if (currentOrder == null) return;

        List<Long> lastApplied;
        synchronized (adapterLastAppliedOrder) {
            List<Long> existing =
                    adapterLastAppliedOrder.get(adapter);
            lastApplied = existing == null
                    ? null
                    : new ArrayList<>(existing);
        }

        if (lastApplied != null
                && lastApplied.equals(currentOrder)) {
            return;
        }

        synchronized (adapterPlaylistIds) {
            adapterPlaylistIds.remove(adapter);
        }

        synchronized (adapterBaseOrder) {
            adapterBaseOrder.put(
                    adapter,
                    new ArrayList<>(currentOrder)
            );
        }

        synchronized (adapterLastAppliedOrder) {
            adapterLastAppliedOrder.remove(adapter);
        }

        boolean remapNotify =
                invokeNoArgVoid(adapter, "fq");

        Log.d(TAG, "Detected Library order change during bind suppression"
                + " remapNotify=" + remapNotify
                + " nativeOrder=" + currentOrder);
    }

    private static boolean isExpectedPlaylistMappingComplete(
            Object adapter
    ) {
        Integer expected;
        synchronized (adapterExpectedPlaylistCount) {
            expected = adapterExpectedPlaylistCount.get(adapter);
        }

        if (expected == null || expected < 3) {
            return false;
        }

        Set<String> distinct = new LinkedHashSet<>();
        synchronized (adapterPlaylistIds) {
            Map<Long, String> mappings =
                    adapterPlaylistIds.get(adapter);

            if (mappings == null) return false;

            for (String playlistId : mappings.values()) {
                if (isPersistentPlaylistId(playlistId)) {
                    distinct.add(playlistId);
                }
            }
        }

        return distinct.size() >= expected;
    }

    private static void scheduleAdapterReorderAfterBinding(
            final Object adapter,
            View anchor,
            boolean mappingComplete
    ) {
        if (mappingComplete) {
            synchronized (adapterBindReorderGeneration) {
                adapterBindReorderGeneration.remove(adapter);
            }

            scheduleAdapterReorder(adapter, anchor);
            return;
        }
        final int generation;

        synchronized (adapterBindReorderGeneration) {
            Integer previous =
                    adapterBindReorderGeneration.get(adapter);
            generation = previous == null ? 1 : previous + 1;
            adapterBindReorderGeneration.put(
                    adapter,
                    generation
            );
        }

        anchor.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (adapterBindReorderGeneration) {
                    Integer current =
                            adapterBindReorderGeneration.get(adapter);

                    if (current == null
                            || current != generation) {
                        return;
                    }

                    adapterBindReorderGeneration.remove(adapter);
                }

                applyPinnedOrder(adapter);
            }
        }, 16L);
    }

    private static void scheduleAdapterReorder(
            final Object adapter,
            View anchor
    ) {
        synchronized (adapterBindReorderGeneration) {
            adapterBindReorderGeneration.remove(adapter);
        }

        synchronized (adapterReorderPending) {
            if (Boolean.TRUE.equals(
                    adapterReorderPending.get(adapter)
            )) {
                return;
            }
            adapterReorderPending.put(adapter, true);
        }

        anchor.post(new Runnable() {
            @Override
            public void run() {
                synchronized (adapterReorderPending) {
                    adapterReorderPending.remove(adapter);
                }

                applyPinnedOrder(adapter);
            }
        });
    }

    private static void applyPinnedOrder(
            Object adapter
    ) {
        Object controller = readFieldByName(adapter, "d");
        Object listObject = readFieldByName(controller, "b");

        if (!(listObject instanceof List)) return;

        List list = (List) listObject;
        if (list.size() < 2) return;

        if (hasActiveAdapterPositionMap(
                adapter,
                list.size()
        )) {
            Log.d(TAG, "PostBindPhysicalReorderSkipped"
                    + " reason=positionMapActiveAtApply"
                    + " adapterIdentity="
                    + identityString(adapter)
                    + " total=" + list.size());
            return;
        }

        Context context = resolveApplicationContext();
        if (context == null) return;

        List<String> pinnedOrder =
                new ArrayList<>(PinStore.getPinnedIds(context));
        Set<String> pinnedIds =
                new LinkedHashSet<>(pinnedOrder);

        Map<Long, String> mappings;
        synchronized (adapterPlaylistIds) {
            Map<Long, String> existing =
                    adapterPlaylistIds.get(adapter);

            if (existing == null || existing.isEmpty()) return;
            mappings = new LinkedHashMap<>(existing);
        }

        rememberAdapterNativeOrder(adapter, list);

        List<Long> base;
        synchronized (adapterBaseOrder) {
            List<Long> existing = adapterBaseOrder.get(adapter);
            if (existing == null) return;
            base = new ArrayList<>(existing);
        }

        Map<Long, Object> itemById = new LinkedHashMap<>();
        for (Object item : (List<?>) list) {
            Object value = readFieldByName(item, "b");
            if (!(value instanceof Number)) return;

            long id = ((Number) value).longValue();
            if (itemById.containsKey(id)) {
                Log.e(TAG, "Skipping reorder: duplicate stable row ID " + id);
                return;
            }
            itemById.put(id, item);
        }

        List<Object> nativeOrder = new ArrayList<>(list.size());
        Set<Long> added = new LinkedHashSet<>();

        for (Long id : base) {
            Object item = itemById.get(id);
            if (item != null) {
                nativeOrder.add(item);
                added.add(id);
            }
        }

        for (Map.Entry<Long, Object> entry : itemById.entrySet()) {
            if (!added.contains(entry.getKey())) {
                nativeOrder.add(entry.getValue());
            }
        }

        List<Integer> playlistSlots = new ArrayList<>();
        List<Object> pinned = new ArrayList<>();
        List<Object> unpinned = new ArrayList<>();
        List<Long> pinnedStableIds = new ArrayList<>();
        Set<String> distinctPlaylistIds = new LinkedHashSet<>();
        Map<String, Object> playlistItemById = new LinkedHashMap<>();
        Map<String, Long> playlistStableIdById = new LinkedHashMap<>();

        for (int index = 0; index < nativeOrder.size(); index++) {
            Object item = nativeOrder.get(index);
            Object value = readFieldByName(item, "b");
            long stableId = ((Number) value).longValue();
            String playlistId = mappings.get(stableId);

            if (!isPersistentPlaylistId(playlistId)) {
                continue;
            }

            playlistSlots.add(index);
            distinctPlaylistIds.add(playlistId);
            playlistItemById.put(playlistId, item);
            playlistStableIdById.put(playlistId, stableId);

            if (!pinnedIds.contains(playlistId)) {
                unpinned.add(item);
            }
        }

        /*
         * Pinned rows follow local pin chronology, never the active Library
         * sort. The first playlist pinned occupies the highest playlist slot,
         * the second occupies the next slot, and so on. Unpinned rows continue
         * to follow YouTube Music's native sort order.
         */
        for (String playlistId : pinnedOrder) {
            Object item = playlistItemById.get(playlistId);
            Long stableId = playlistStableIdById.get(playlistId);

            if (item == null || stableId == null) continue;

            pinned.add(item);
            pinnedStableIds.add(stableId);
        }

        if (distinctPlaylistIds.size() < 3
                && adapter != activeLibraryAdapter) {
            return;
        }

        if (distinctPlaylistIds.size() >= 3) {
            lastKnownLibraryPlaylistCount =
                    distinctPlaylistIds.size();

            synchronized (adapterExpectedPlaylistCount) {
                adapterExpectedPlaylistCount.put(
                        adapter,
                        distinctPlaylistIds.size()
                );
            }
        }

        List<Object> partitionedPlaylists =
                new ArrayList<>(playlistSlots.size());
        partitionedPlaylists.addAll(pinned);
        partitionedPlaylists.addAll(unpinned);

        List<Object> desired = new ArrayList<>(nativeOrder);

        for (int index = 0; index < playlistSlots.size(); index++) {
            desired.set(
                    playlistSlots.get(index),
                    partitionedPlaylists.get(index)
            );
        }

        if (sameIdentityOrder(list, desired)) {
            synchronized (adapterLastAppliedOrder) {
                adapterLastAppliedOrder.put(
                        adapter,
                        stableIdsForItems(list)
                );
            }
            return;
        }

        /*
         * Keep a short multi-frame guard around native Litho moves. The
         * stable-row remap guard now rejects recycled-holder conflicts, so the
         * earlier three-second suppression window only delayed rapid refreshes
         * and sort changes.
         */
        suppressAdapterBindCapture(adapter, 150L);

        List<String> nativeMoveNotifications =
                new ArrayList<>();
        List<String> fallbackMoveNotifications =
                new ArrayList<>();
        boolean usedFallbackMove = false;
        boolean fallbackFullNotify = false;

        try {
            /*
             * Use Litho's own adapter move pipeline. hyz.z(source, target)
             * delegates to hzc.I(source, target), allowing the renderer to
             * move its backing item and ComponentTree together. The earlier
             * remove/add + RecyclerView notification path changed hzc.b but
             * did not reliably keep the on-screen Litho rows aligned after a
             * Library sort rebuild.
             */
            for (int targetIndex = 0;
                 targetIndex < desired.size();
                 targetIndex++) {
                Object desiredItem = desired.get(targetIndex);

                if (list.get(targetIndex) == desiredItem) {
                    continue;
                }

                int sourceIndex = indexOfIdentity(
                        list,
                        desiredItem,
                        targetIndex + 1
                );

                if (sourceIndex < 0) {
                    throw new IllegalStateException(
                            "Desired Library item was not found"
                                    + " targetIndex=" + targetIndex
                    );
                }

                boolean nativeMoveInvoked = invokeTwoIntVoid(
                        adapter,
                        "z",
                        sourceIndex,
                        targetIndex
                );

                boolean nativeListMoved =
                        targetIndex < list.size()
                                && list.get(targetIndex) == desiredItem;

                nativeMoveNotifications.add(
                        sourceIndex + "->" + targetIndex
                                + ":invoked=" + nativeMoveInvoked
                                + ":listMoved=" + nativeListMoved
                );

                if (nativeMoveInvoked && nativeListMoved) {
                    continue;
                }

                /*
                 * Safety fallback for version drift. Only use the old direct
                 * list mutation when the native move path is absent or did
                 * not synchronously place the expected item.
                 */
                int fallbackSource = indexOfIdentity(
                        list,
                        desiredItem,
                        0
                );

                if (fallbackSource < 0) {
                    throw new IllegalStateException(
                            "Fallback Library item was not found"
                                    + " targetIndex=" + targetIndex
                    );
                }

                Object movedItem = list.remove(fallbackSource);
                list.add(targetIndex, movedItem);

                boolean moveNotified = invokeTwoIntVoid(
                        adapter,
                        "jv",
                        fallbackSource,
                        targetIndex
                );

                fallbackMoveNotifications.add(
                        fallbackSource + "->" + targetIndex
                                + ":" + moveNotified
                );

                usedFallbackMove = true;

                if (!moveNotified) {
                    fallbackFullNotify = true;
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "Failed moving Library rows", error);
            return;
        }

        List<Long> applied = stableIdsForItems(list);
        synchronized (adapterLastAppliedOrder) {
            adapterLastAppliedOrder.put(adapter, applied);
        }

        if (fallbackFullNotify) {
            fallbackFullNotify = invokeNoArgVoid(adapter, "fq");
        }

        Log.d(TAG, "Applied pinned Library order"
                + " total=" + list.size()
                + " pinnedRows=" + pinned.size()
                + " pinnedStableIds=" + pinnedStableIds
                + " pinOrder=" + pinnedOrder
                + " playlistSlots=" + playlistSlots
                + " bindCaptureSuppressedMs=150"
                + " nativeMoveNotifications="
                + nativeMoveNotifications
                + " usedFallbackMove=" + usedFallbackMove
                + " fallbackMoveNotifications="
                + fallbackMoveNotifications
                + " fallbackFullNotify=" + fallbackFullNotify
                + " order=" + applied);
    }

    private static boolean isPersistentPlaylistId(
            @Nullable String playlistId
    ) {
        if (playlistId == null) return false;

        return playlistId.startsWith("PL")
                || playlistId.startsWith("OLAK5uy_")
                || playlistId.startsWith("LRSR");
    }

    private static int indexOfIdentity(
            List<?> values,
            Object target,
            int startIndex
    ) {
        int first = Math.max(0, startIndex);

        for (int index = first;
             index < values.size();
             index++) {
            if (values.get(index) == target) {
                return index;
            }
        }

        return -1;
    }

    private static boolean sameIdentityOrder(
            List<?> current,
            List<?> desired
    ) {
        if (current.size() != desired.size()) return false;

        for (int index = 0; index < current.size(); index++) {
            if (current.get(index) != desired.get(index)) {
                return false;
            }
        }

        return true;
    }


    private static boolean invokeTwoIntVoid(
            Object receiver,
            String methodName,
            int first,
            int second
    ) {
        for (Class<?> current = receiver.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(
                        methodName,
                        Integer.TYPE,
                        Integer.TYPE
                );

                if (method.getReturnType() != Void.TYPE) {
                    continue;
                }

                method.setAccessible(true);
                method.invoke(receiver, first, second);
                return true;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable error) {
                Log.e(TAG, "Failed invoking "
                        + current.getName() + "." + methodName
                        + "(" + first + ", " + second + ")", error);
                return false;
            }
        }

        return false;
    }

    private static boolean invokeNoArgVoid(
            Object receiver,
            String methodName
    ) {
        for (Class<?> current = receiver.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(methodName);
                if (method.getParameterTypes().length != 0
                        || method.getReturnType() != Void.TYPE) {
                    continue;
                }

                method.setAccessible(true);
                method.invoke(receiver);
                return true;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable error) {
                Log.e(TAG, "Failed invoking "
                        + current.getName() + "." + methodName, error);
                return false;
            }
        }

        return false;
    }

    @Nullable
    private static String findOnlySupportedPlaylistId(
            Set<String> candidates
    ) {
        String resolved = null;

        if (candidates == null) return null;

        for (String candidate : candidates) {
            if (!PinStore.isSupportedPlaylistId(candidate)) {
                continue;
            }

            if (resolved != null
                    && !resolved.equals(candidate)) {
                Log.d(TAG, "FlyoutPageIdentityBridge"
                        + " rejectedAmbiguousIds=" + candidates);
                return null;
            }

            resolved = candidate;
        }

        return resolved;
    }

    private static Set<String> collectPlaylistIdsFromFlyoutView(
            View clickedView
    ) {
        Set<String> ids = new LinkedHashSet<>();
        View currentView = clickedView;

        for (int depth = 0;
             depth < 12 && currentView != null && ids.isEmpty();
             depth++) {
            collectIdsFromByhmCarrier(
                    currentView.getTag(),
                    ids,
                    4,
                    new IdentityHashMap<Object, Boolean>()
            );

            Object keyedTags =
                    readFieldByName(currentView, "mKeyedTags");

            if (ids.isEmpty() && keyedTags instanceof SparseArray) {
                SparseArray<?> tags = (SparseArray<?>) keyedTags;
                int count = Math.min(tags.size(), 30);

                for (int index = 0;
                     index < count && ids.isEmpty();
                     index++) {
                    collectIdsFromByhmCarrier(
                            tags.valueAt(index),
                            ids,
                            4,
                            new IdentityHashMap<Object, Boolean>()
                    );
                }
            }

            Object listenerInfo =
                    readFieldByName(currentView, "mListenerInfo");

            if (ids.isEmpty() && listenerInfo != null) {
                for (Field field :
                        getInstanceFields(listenerInfo.getClass())) {
                    if (field.getType().isPrimitive()) continue;

                    try {
                        field.setAccessible(true);
                        collectIdsFromByhmCarrier(
                                field.get(listenerInfo),
                                ids,
                                6,
                                new IdentityHashMap<Object, Boolean>()
                        );
                    } catch (Throwable ignored) {
                    }

                    if (!ids.isEmpty()) break;
                }
            }

            Object parent = currentView.getParent();
            currentView = parent instanceof View
                    ? (View) parent
                    : null;
        }

        return ids;
    }

    @Nullable
    private static String resolveFlyoutViewPlaylistId(
            View clickedView,
            Object sourceObject
    ) {
        String playlistId = findBestPlaylistId(
                collectObjectStrings(sourceObject)
        );

        if (PinStore.isSupportedPlaylistId(playlistId)) {
            return playlistId;
        }

        playlistId = findOnlySupportedPlaylistId(
                collectCanonicalPlaylistIds(sourceObject, 14)
        );

        if (PinStore.isSupportedPlaylistId(playlistId)) {
            Log.d(TAG, "FlyoutPageIdentityBridge"
                    + " source=canonicalSourceGraph"
                    + " playlistId=" + playlistId
                    + " sourceType=" + objectTypeName(sourceObject));
            return playlistId;
        }

        Set<String> viewIds =
                collectPlaylistIdsFromFlyoutView(clickedView);
        playlistId = findOnlySupportedPlaylistId(viewIds);

        if (PinStore.isSupportedPlaylistId(playlistId)) {
            Log.d(TAG, "FlyoutPageIdentityBridge"
                    + " source=viewCarrier"
                    + " playlistId=" + playlistId
                    + " sourceType=" + objectTypeName(sourceObject));
        }

        return playlistId;
    }

    public static void captureFlyoutViewContext(
            @Nullable View clickedView,
            @Nullable Object sourceObject
    ) {
        try {
            captureFlyoutViewContextInternal(
                    clickedView,
                    sourceObject
            );
        } catch (Throwable error) {
            Log.e(TAG, "Failed capturing flyout View context", error);
            clearActiveFlyoutRowContext();
            pendingFlyoutViewPlaylistId = null;
            pendingFlyoutViewCapturedAtMs = 0L;
        }
    }

    private static void captureFlyoutViewContextInternal(
            @Nullable View clickedView,
            @Nullable Object sourceObject
    ) {
        if (!isFeatureEnabled()) {
            return;
        }
        if (!flyoutViewEntryLogged) {
            flyoutViewEntryLogged = true;
            Log.d(TAG, "DiagnosticBuild=" + BUILD_ID
                    + " flyoutViewHook=true"
                    + " viewType=" + objectTypeName(clickedView)
                    + " sourceType=" + objectTypeName(sourceObject));
        }

        clearActiveFlyoutRowContext();
        pendingFlyoutViewPlaylistId = null;
        pendingFlyoutViewCapturedAtMs = 0L;

        if (clickedView == null || sourceObject == null) return;

        String playlistId = resolveFlyoutViewPlaylistId(
                clickedView,
                sourceObject
        );
        if (!PinStore.isSupportedPlaylistId(playlistId)) {
            Log.d(TAG, "FlyoutRowKey playlistId unavailable");
            return;
        }

        activeFlyoutRowPlaylistId = playlistId;
        rememberPendingFlyoutViewPlaylistId(playlistId);

        View descendant = clickedView;
        Object current = clickedView;

        for (int depth = 0; depth < 14 && current != null; depth++) {
            if (current instanceof View
                    && current.getClass().getName().contains("RecyclerView")) {
                diagnoseFlyoutRecyclerRow(
                        current,
                        descendant,
                        playlistId
                );
                return;
            }

            if (current instanceof View) {
                descendant = (View) current;
                current = ((View) current).getParent();
            } else {
                current = invokeNoArg(current, "getParent");
            }
        }

        Log.d(TAG, "FlyoutRowKey RecyclerView not found"
                + " playlistId=" + playlistId);
    }

    private static void diagnoseFlyoutRecyclerRow(
            Object recycler,
            View directChild,
            String playlistId
    ) {
        Object adapter = readFieldByName(recycler, "m");

        if (!isLibraryAdapter(adapter)) {
            adapter = findFieldValueByRuntimeTypes(
                    recycler,
                    LIBRARY_ADAPTER_CLASSES
            );
        }

        Object controller = readFieldByName(adapter, "d");
        Object listObject = readFieldByName(controller, "b");

        if (!(listObject instanceof List)) {
            Log.d(TAG, "FlyoutRowKey backing list unavailable"
                    + " playlistId=" + playlistId
                    + " recyclerType=" + objectTypeName(recycler)
                    + " adapterType=" + objectTypeName(adapter)
                    + " controllerType=" + objectTypeName(controller)
                    + " listType=" + objectTypeName(listObject));
            return;
        }

        List<?> items = (List<?>) listObject;
        int childIndex = -1;

        if (recycler instanceof ViewGroup) {
            childIndex = ((ViewGroup) recycler).indexOfChild(directChild);
        }

        Object holder = findViewHolder(
                recycler,
                directChild
        );

        Map<String, Integer> recyclerPositions =
                invokeViewIntMethods(recycler, directChild);
        Map<String, Integer> holderPositions =
                invokeNoArgIntMethods(holder);
        Map<String, Long> holderPrimitiveFields =
                readPrimitiveNumberFields(holder);

        List<Long> stableIds = new ArrayList<>();
        for (Object item : items) {
            Object stableId = readFieldByName(item, "b");
            stableIds.add(stableId instanceof Number
                    ? ((Number) stableId).longValue()
                    : null);
        }

        int resolvedPosition = resolveRowPosition(
                items.size(),
                stableIds,
                recyclerPositions,
                holderPositions,
                holderPrimitiveFields
        );

        Long stableRowId = resolvedPosition >= 0
                && resolvedPosition < stableIds.size()
                ? stableIds.get(resolvedPosition)
                : null;

        activeFlyoutAdapterPosition = resolvedPosition;
        activeFlyoutStableRowId = stableRowId;
        activeFlyoutRowView = directChild;
        activeLibraryAdapter = adapter;
        activeLibraryBackingList = items;

        if (stableRowId != null) {
            rememberAdapterPlaylistId(
                    adapter,
                    stableRowId,
                    playlistId
            );
            rememberAdapterNativeOrder(adapter, items);
        }

        Log.d(TAG, "FlyoutRowKey"
                + " playlistId=" + playlistId
                + " adapterType=" + objectTypeName(adapter)
                + " total=" + items.size()
                + " childIndex=" + childIndex
                + " rowType=" + objectTypeName(directChild)
                + " rowText=" + collectRowText(directChild)
                + " holderType=" + objectTypeName(holder)
                + " recyclerPositions=" + recyclerPositions
                + " holderPositions=" + holderPositions
                + " holderFields=" + holderPrimitiveFields
                + " stableIds=" + stableIds
                + " resolvedPosition=" + resolvedPosition
                + " stableRowId=" + stableRowId);
    }

    @Nullable
    private static Object findFieldValueByRuntimeType(
            @Nullable Object receiver,
            String runtimeTypeName
    ) {
        if (receiver == null) return null;

        for (Class<?> current = receiver.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType().isPrimitive()) continue;

                try {
                    field.setAccessible(true);
                    Object value = field.get(receiver);

                    if (value != null
                            && runtimeTypeName.equals(
                            value.getClass().getName()
                    )) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    @Nullable
    private static Object findFieldValueByRuntimeTypes(
            @Nullable Object receiver,
            String[] runtimeTypeNames
    ) {
        for (String runtimeTypeName : runtimeTypeNames) {
            Object value = findFieldValueByRuntimeType(
                    receiver,
                    runtimeTypeName
            );

            if (value != null) return value;
        }

        return null;
    }

    @Nullable
    private static Object findViewHolder(
            Object recycler,
            View directChild
    ) {
        ViewGroup.LayoutParams layoutParams =
                directChild.getLayoutParams();

        if (layoutParams != null) {
            for (Class<?> current = layoutParams.getClass();
                 current != null && current != Object.class;
                 current = current.getSuperclass()) {
                Field[] fields;
                try {
                    fields = current.getDeclaredFields();
                } catch (Throwable ignored) {
                    continue;
                }

                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType().isPrimitive()) continue;

                    try {
                        field.setAccessible(true);
                        Object value = field.get(layoutParams);

                        if (isViewHolderObject(value)) {
                            Log.d(TAG, "FlyoutRowHolder"
                                    + " source=layoutParams"
                                    + " field=" + current.getName()
                                    + "." + field.getName()
                                    + " valueType="
                                    + value.getClass().getName());
                            return value;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        for (Class<?> current = recycler.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Method[] methods;
            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }

            for (Method method : methods) {
                if (Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterTypes().length != 1) continue;
                if (!method.getParameterTypes()[0].isAssignableFrom(
                        directChild.getClass()
                )) continue;
                if (method.getReturnType().isPrimitive()) continue;
                if (method.getReturnType() == Void.TYPE) continue;
                if (!isViewHolderClass(method.getReturnType())) continue;

                try {
                    method.setAccessible(true);
                    Object value = method.invoke(recycler, directChild);

                    if (isViewHolderObject(value)) {
                        Log.d(TAG, "FlyoutRowHolder"
                                + " source=method"
                                + " method=" + current.getName()
                                + "." + method.getName()
                                + " valueType="
                                + value.getClass().getName());
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    private static boolean isViewHolderObject(
            @Nullable Object value
    ) {
        return value != null && isViewHolderClass(value.getClass());
    }

    private static boolean isViewHolderClass(
            @Nullable Class<?> type
    ) {
        for (Class<?> current = type;
             current != null;
             current = current.getSuperclass()) {
            if ("vt".equals(current.getName())) return true;
        }

        return false;
    }

    private static Map<String, Integer> invokeViewIntMethods(
            Object receiver,
            View argument
    ) {
        Map<String, Integer> results = new java.util.LinkedHashMap<>();

        for (Class<?> current = receiver.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Method[] methods;
            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }

            for (Method method : methods) {
                if (Modifier.isStatic(method.getModifiers())) continue;
                if (method.getReturnType() != Integer.TYPE) continue;
                if (method.getParameterTypes().length != 1) continue;
                if (!method.getParameterTypes()[0].isAssignableFrom(
                        argument.getClass()
                )) continue;

                try {
                    method.setAccessible(true);
                    Object value = method.invoke(receiver, argument);
                    if (value instanceof Number) {
                        results.put(
                                current.getName() + "." + method.getName(),
                                ((Number) value).intValue()
                        );
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return results;
    }

    private static Map<String, Integer> invokeNoArgIntMethods(
            @Nullable Object receiver
    ) {
        Map<String, Integer> results = new java.util.LinkedHashMap<>();
        if (receiver == null) return results;

        for (Class<?> current = receiver.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Method[] methods;
            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }

            for (Method method : methods) {
                if (Modifier.isStatic(method.getModifiers())) continue;
                if (method.getReturnType() != Integer.TYPE) continue;
                if (method.getParameterTypes().length != 0) continue;

                try {
                    method.setAccessible(true);
                    Object value = method.invoke(receiver);
                    if (value instanceof Number) {
                        results.put(
                                current.getName() + "." + method.getName(),
                                ((Number) value).intValue()
                        );
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return results;
    }

    private static Map<String, Long> readPrimitiveNumberFields(
            @Nullable Object receiver
    ) {
        Map<String, Long> results = new java.util.LinkedHashMap<>();
        if (receiver == null) return results;

        for (Class<?> current = receiver.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;

                Class<?> type = field.getType();
                if (type != Integer.TYPE
                        && type != Long.TYPE
                        && type != Short.TYPE
                        && type != Byte.TYPE) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object value = field.get(receiver);
                    if (value instanceof Number) {
                        results.put(
                                current.getName() + "." + field.getName(),
                                ((Number) value).longValue()
                        );
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return results;
    }

    private static int resolveRowPosition(
            int itemCount,
            List<Long> stableIds,
            Map<String, Integer> recyclerPositions,
            Map<String, Integer> holderPositions,
            Map<String, Long> holderFields
    ) {
        /*
         * Strongest signal: RecyclerView View->int methods. Obfuscation changes
         * their names, but getChildAdapterPosition/getChildLayoutPosition retain
         * this exact signature and return the same in-range position.
         */
        Set<Integer> recyclerCandidates = new LinkedHashSet<>();
        for (Integer value : recyclerPositions.values()) {
            if (value != null && value >= 0 && value < itemCount) {
                recyclerCandidates.add(value);
            }
        }

        if (recyclerCandidates.size() == 1) {
            return recyclerCandidates.iterator().next();
        }

        /*
         * Next strongest signal: the holder's stored item-id long matching
         * hyz.iv(position), which is hvg.b converted to long.
         */
        for (Long fieldValue : holderFields.values()) {
            if (fieldValue == null) continue;

            for (int index = 0; index < stableIds.size(); index++) {
                Long stableId = stableIds.get(index);
                if (stableId != null && stableId.equals(fieldValue)) {
                    return index;
                }
            }
        }

        Set<Integer> holderCandidates = new LinkedHashSet<>();

        for (Integer value : holderPositions.values()) {
            if (value != null && value >= 0 && value < itemCount) {
                holderCandidates.add(value);
            }
        }

        for (Long value : holderFields.values()) {
            if (value != null && value >= 0 && value < itemCount) {
                holderCandidates.add(value.intValue());
            }
        }

        if (holderCandidates.size() == 1) {
            return holderCandidates.iterator().next();
        }

        return -1;
    }

    private static String collectRowText(View root) {
        List<String> values = collectRowTextValues(root);

        if (values.isEmpty()) return "[]";
        return values.toString();
    }

    private static List<String> collectRowTextValues(
            View root
    ) {
        List<String> values = new ArrayList<>();
        collectRowTextRecursive(root, values, 0);
        return values;
    }

    private static void collectRowTextRecursive(
            View view,
            List<String> values,
            int depth
    ) {
        if (view == null || depth > 8 || values.size() >= 12) return;

        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) {
                String value = text.toString().trim();
                if (!value.isEmpty() && !values.contains(value)) {
                    values.add(value);
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 30);

            for (int index = 0; index < count; index++) {
                collectRowTextRecursive(
                        group.getChildAt(index),
                        values,
                        depth + 1
                );
            }
        }
    }







    @Nullable
    private static Object invokeNoArg(Object receiver, String methodName) {
        if (receiver == null) return null;

        try {
            Method method = receiver.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Object invokeOneIntArgument(
            @Nullable Object receiver,
            String methodName,
            int argument
    ) {
        if (receiver == null) return null;

        for (Class<?> current = receiver.getClass();
             current != null;
             current = current.getSuperclass()) {
            Method[] methods;

            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }

            for (Method method : methods) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }

                Class<?>[] parameters =
                        method.getParameterTypes();

                if (parameters.length != 1
                        || parameters[0] != int.class) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    return method.invoke(
                            receiver,
                            argument
                    );
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    @Nullable
    private static Integer invokeIntNoArg(Object receiver, String methodName) {
        Object result = invokeNoArg(receiver, methodName);
        return result instanceof Number ? ((Number) result).intValue() : null;
    }







    private static String objectTypeName(@Nullable Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static boolean isLibraryAdapter(@Nullable Object value) {
        if (value == null) return false;

        String typeName = value.getClass().getName();
        for (String candidate : LIBRARY_ADAPTER_CLASSES) {
            if (candidate.equals(typeName)) return true;
        }

        return false;
    }


    @Nullable
    private static String consumeActiveFlyoutPlaylistId() {
        String id = activeFlyoutPlaylistId;
        long capturedAt = activeFlyoutCapturedAtMs;
        long now = android.os.SystemClock.elapsedRealtime();

        activeFlyoutPlaylistId = null;
        activeFlyoutCapturedAtMs = 0L;

        if (id == null) return null;
        if (capturedAt <= 0L) return null;
        if (now - capturedAt > ACTIVE_FLYOUT_ID_TTL_MS) {
            Log.d(TAG, "Discarded stale active flyout playlist id=" + id);
            return null;
        }

        return id;
    }

    @Nullable
    private static String lookupPlaylistIdFromPresenterGraph(
            @Nullable Object presenter
    ) {
        if (presenter == null) return null;

        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        int[] visitedCount = new int[]{0};

        return lookupPlaylistIdFromPresenterGraphRecursive(
                presenter,
                visited,
                visitedCount,
                0
        );
    }

    @Nullable
    private static String lookupPlaylistIdFromPresenterGraphRecursive(
            @Nullable Object value,
            IdentityHashMap<Object, Boolean> visited,
            int[] visitedCount,
            int depth
    ) {
        if (value == null || depth > MAX_REFLECTION_DEPTH) return null;
        if (visitedCount[0] >= MAX_VISITED_OBJECTS_PER_ROW) return null;

        synchronized (flyoutPresenterIds) {
            String mapped = flyoutPresenterIds.get(value);
            if (mapped != null) return mapped;
        }

        Class<?> type = value.getClass();
        if (isTerminalType(type) || type.isPrimitive()) return null;
        if (value instanceof byte[] || isByteContainer(type)) return null;

        if (visited.put(value, Boolean.TRUE) != null) return null;
        visitedCount[0]++;

        if (type.isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                String found = lookupPlaylistIdFromPresenterGraphRecursive(
                        Array.get(value, index),
                        visited,
                        visitedCount,
                        depth + 1
                );
                if (found != null) return found;
            }
            return null;
        }

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                String found = lookupPlaylistIdFromPresenterGraphRecursive(
                        item,
                        visited,
                        visitedCount,
                        depth + 1
                );
                if (found != null) return found;
            }
            return null;
        }

        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String found = lookupPlaylistIdFromPresenterGraphRecursive(
                        entry.getKey(),
                        visited,
                        visitedCount,
                        depth + 1
                );
                if (found != null) return found;

                found = lookupPlaylistIdFromPresenterGraphRecursive(
                        entry.getValue(),
                        visited,
                        visitedCount,
                        depth + 1
                );
                if (found != null) return found;
            }
            return null;
        }

        for (Class<?> current = type;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable error) {
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType().isPrimitive()) continue;

                try {
                    field.setAccessible(true);
                    String found = lookupPlaylistIdFromPresenterGraphRecursive(
                            field.get(value),
                            visited,
                            visitedCount,
                            depth + 1
                    );
                    if (found != null) return found;
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    @Nullable
    private static String lookupFlyoutPlaylistId(@Nullable Object menuItem) {
        if (menuItem == null) return null;

        synchronized (flyoutObjectIds) {
            return flyoutObjectIds.get(menuItem);
        }
    }


    private static void collectDiagnosticStringsRecursive(
            @Nullable Object value,
            Set<String> candidates,
            IdentityHashMap<Object, Boolean> visited,
            int[] visitedCount,
            int depth
    ) {
        if (value == null || depth > MAX_REFLECTION_DEPTH) return;
        if (visitedCount[0] >= MAX_VISITED_OBJECTS_PER_ROW) return;
        if (candidates.size() >= MAX_DIAGNOSTIC_STRINGS) return;

        if (value instanceof CharSequence) {
            addDiagnosticString(value.toString(), candidates);
            return;
        }

        byte[] bytes = extractByteContainer(value);
        if (bytes != null && bytes.length != 0) {
            addPrintableCandidates(bytes, candidates);

            if (value instanceof byte[] || isByteContainer(value.getClass())) {
                return;
            }
        }

        Class<?> type = value.getClass();
        if (isTerminalType(type)) return;

        if (visited.put(value, Boolean.TRUE) != null) return;
        visitedCount[0]++;

        if (type.isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                collectDiagnosticStringsRecursive(
                        Array.get(value, index),
                        candidates,
                        visited,
                        visitedCount,
                        depth + 1
                );
                if (candidates.size() >= MAX_DIAGNOSTIC_STRINGS) return;
            }
            return;
        }

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                collectDiagnosticStringsRecursive(
                        item,
                        candidates,
                        visited,
                        visitedCount,
                        depth + 1
                );
                if (candidates.size() >= MAX_DIAGNOSTIC_STRINGS) return;
            }
            return;
        }

        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                collectDiagnosticStringsRecursive(
                        entry.getKey(),
                        candidates,
                        visited,
                        visitedCount,
                        depth + 1
                );
                collectDiagnosticStringsRecursive(
                        entry.getValue(),
                        candidates,
                        visited,
                        visitedCount,
                        depth + 1
                );
                if (candidates.size() >= MAX_DIAGNOSTIC_STRINGS) return;
            }
            return;
        }

        for (Class<?> current = type;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable error) {
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType().isPrimitive()) continue;

                try {
                    field.setAccessible(true);
                    collectDiagnosticStringsRecursive(
                            field.get(value),
                            candidates,
                            visited,
                            visitedCount,
                            depth + 1
                    );
                } catch (Throwable ignored) {
                }

                if (candidates.size() >= MAX_DIAGNOSTIC_STRINGS) return;
            }
        }
    }

    private static void logCandidateSet(
            String prefix,
            Set<String> candidates
    ) {
        if (candidates == null || candidates.isEmpty()) {
            Log.d(TAG, prefix + "[none]");
            return;
        }

        int index = 0;
        for (String candidate : candidates) {
            Log.d(TAG, prefix + "[" + index + "]=" + candidate);
            index++;
        }
    }

    private static void addDiagnosticString(
            @Nullable String value,
            Set<String> diagnosticStrings
    ) {
        if (value == null || diagnosticStrings.size() >= MAX_DIAGNOSTIC_STRINGS) {
            return;
        }

        String candidate = value.trim();
        if (candidate.length() < 3 || candidate.length() > 180) return;

        // Keep likely YouTube identifiers and short human-readable protobuf
        // strings. This is diagnostic only and does not affect row ordering.
        boolean likelyIdentifier =
                candidate.startsWith("VL")
                || candidate.startsWith("PL")
                || candidate.startsWith("OLAK5uy_")
                || candidate.startsWith("RD")
                || candidate.startsWith("UC")
                || candidate.startsWith("MPRE")
                || candidate.contains("playlist")
                || candidate.contains("browse")
                || candidate.contains("navigation");

        boolean readable = true;
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                readable = false;
                break;
            }
        }

        if (likelyIdentifier || (readable && candidate.length() <= 80)) {
            diagnosticStrings.add(candidate);
        }
    }

    /**
     * Pulls printable protobuf string payloads out of serialized bytes without
     * needing the obfuscated schema. Protobuf length-delimited strings usually
     * appear as contiguous printable ASCII runs.
     */
    private static void addPrintableCandidates(
            byte[] bytes,
            Set<String> diagnosticStrings
    ) {
        if (diagnosticStrings.size() >= MAX_DIAGNOSTIC_STRINGS) return;

        int start = -1;

        for (int index = 0; index <= bytes.length; index++) {
            boolean printable =
                    index < bytes.length
                    && bytes[index] >= 0x20
                    && bytes[index] <= 0x7e;

            if (printable) {
                if (start < 0) start = index;
                continue;
            }

            if (start >= 0) {
                int length = index - start;
                if (length >= 3 && length <= 180) {
                    try {
                        addDiagnosticString(
                                new String(
                                        bytes,
                                        start,
                                        length,
                                        java.nio.charset.StandardCharsets.UTF_8
                                ),
                                diagnosticStrings
                        );
                    } catch (Throwable ignored) {
                    }
                }
                start = -1;

                if (diagnosticStrings.size() >= MAX_DIAGNOSTIC_STRINGS) {
                    return;
                }
            }
        }
    }

    /**
     * Returns bytes from either a raw byte[] or a protobuf ByteString-style
     * wrapper. Generated protobuf models commonly store endpoint payloads in
     * ByteString objects rather than directly in byte[] fields.
     */
    @Nullable
    private static byte[] extractByteContainer(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof byte[]) return (byte[]) value;

        Class<?> type = value.getClass();
        if (!isByteContainer(type)) return null;

        try {
            Method method = type.getMethod("toByteArray");
            if (method.getParameterTypes().length == 0
                    && method.getReturnType() == byte[].class) {
                Object result = method.invoke(value);
                return result instanceof byte[] ? (byte[]) result : null;
            }
        } catch (Throwable ignored) {
        }

        try {
            Method method = type.getDeclaredMethod("toByteArray");
            method.setAccessible(true);
            if (method.getParameterTypes().length == 0
                    && method.getReturnType() == byte[].class) {
                Object result = method.invoke(value);
                return result instanceof byte[] ? (byte[]) result : null;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static boolean isByteContainer(Class<?> type) {
        if (type == byte[].class) return true;

        String name = type.getName();
        if (name.contains("ByteString")) return true;

        // Obfuscation may rename the class while retaining the public protobuf
        // API method. Only accept an exact no-argument toByteArray() -> byte[].
        for (Method method : type.getMethods()) {
            if (method.getName().equals("toByteArray")
                    && method.getParameterTypes().length == 0
                    && method.getReturnType() == byte[].class) {
                return true;
            }
        }

        return false;
    }

    private static boolean isTerminalType(Class<?> type) {
        if (type.isPrimitive() || type.isEnum()) return true;

        String name = type.getName();

        return name.equals("java.lang.String")
                || name.equals("java.lang.Class")
                || name.equals("java.lang.Boolean")
                || name.equals("java.lang.Byte")
                || name.equals("java.lang.Short")
                || name.equals("java.lang.Integer")
                || name.equals("java.lang.Long")
                || name.equals("java.lang.Float")
                || name.equals("java.lang.Double")
                || name.equals("java.lang.Character")
                || name.startsWith("java.lang.reflect.")
                || name.startsWith("android.view.")
                || name.startsWith("android.graphics.")
                || name.startsWith("android.content.res.");
    }

    @Nullable
    private static Context resolveApplicationContext() {
        Context cached = applicationContext;
        if (cached != null) return cached;

        try {
            Class<?> activityThread =
                    Class.forName("android.app.ActivityThread");
            Method currentApplication =
                    activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);

            Object application = currentApplication.invoke(null);
            if (application instanceof Context) {
                Context context =
                        ((Context) application).getApplicationContext();
                applicationContext = context;
                return context;
            }
        } catch (Throwable error) {
            Log.e(TAG, "Unable to resolve application Context", error);
        }

        return null;
    }

    private PinPlaylistPatch() {
    }
}
