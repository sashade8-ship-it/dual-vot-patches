/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.addon;

import android.os.Looper;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Loads add-ons that are patched into the app by third party add-on patch bundles.
 * <p>
 * Add-on bundles are loaded by the patcher in their own class loader and cannot reference
 * any Morphe patch code at patch time. Instead, an add-on bundle inserts a static call to
 * its own registration method into {@link #registerAddOns()} while patching, and that
 * registration method subscribes to the hooks declared in {@link AddOnApi}.
 * <p>
 * The method signatures of this class and of {@link AddOnApi} are a public contract.
 * Renaming or removing them breaks every add-on bundle that was built against them.
 */
public final class AddOnManager {

    private static final AddOnLoadCoordinator loadCoordinator = new AddOnLoadCoordinator(
            new AddOnLoadCoordinator.MainThreadDispatcher() {
                @Override
                public boolean isOnMainThread() {
                    Looper mainLooper = Looper.getMainLooper();
                    return mainLooper != null && Looper.myLooper() == mainLooper;
                }

                @Override
                public void verifyOnMainThread() {
                    Utils.verifyOnMainThread();
                }

                @Override
                public void runOnMainThread(Runnable action) {
                    Utils.runOnMainThread(action);
                }
            },
            AddOnManager::registerAddOns,
            failure -> Logger.printException(() -> "Add-on registration failure", failure)
    );

    /**
     * Injection point. Called when the app context is available.
     */
    public static void initialize() {
        ensureLoaded();
    }

    /**
     * Loads all add-ons, if not done already. Safe to call from any thread and any number of times.
     */
    public static void ensureLoaded() {
        loadCoordinator.ensureLoaded();
    }

    /**
     * Add-on injection point. Add-on patch bundles add a call to their own static
     * registration method to this method while patching.
     * <p>
     * Do not rename, do not remove, and do not change the signature.
     */
    public static void registerAddOns() {
        Logger.printDebug(() -> "Registering add-ons");
    }

    private AddOnManager() {
    }
}
