package org.icij.datashare.policies;

import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.WatcherEx;
import org.casbin.watcherEx.RedisWatcherEx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Wraps RedisWatcherEx so that publish failures are logged at WARN level.
 * Without this, jcasbin's notifyWatcher() catches exceptions from updateFor*()
 * and calls Util.logPrint() — INFO-level, enableLog-gated, no stack trace —
 * so a Redis outage during project grant exits 0 with no operator-visible warning.
 */
public class SafeWatcherEx implements WatcherEx {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafeWatcherEx.class);
    private static final String WARN_MSG = "Policy-reload notification failed;"
            + " other server instances will not see this grant until restarted or auto-reload fires."
            + " Cause: {}";
    private final RedisWatcherEx delegate;

    public SafeWatcherEx(RedisWatcherEx delegate) {
        this.delegate = delegate;
    }

    @Override
    public void setUpdateCallback(Runnable r) { delegate.setUpdateCallback(r); }

    @Override
    public void setUpdateCallback(Consumer<String> c) { delegate.setUpdateCallback(c); }

    @Override
    public void update() {
        try { delegate.update(); } catch (Exception e) { LOGGER.warn(WARN_MSG, e.getMessage()); }
    }

    @Override
    public void updateForAddPolicy(String sec, String ptype, String... params) {
        try { delegate.updateForAddPolicy(sec, ptype, params); } catch (Exception e) { LOGGER.warn(WARN_MSG, e.getMessage()); }
    }

    @Override
    public void updateForRemovePolicy(String sec, String ptype, String... params) {
        try { delegate.updateForRemovePolicy(sec, ptype, params); } catch (Exception e) { LOGGER.warn(WARN_MSG, e.getMessage()); }
    }

    @Override
    public void updateForRemoveFilteredPolicy(String sec, String ptype, int fieldIndex, String... fieldValues) {
        try { delegate.updateForRemoveFilteredPolicy(sec, ptype, fieldIndex, fieldValues); } catch (Exception e) { LOGGER.warn(WARN_MSG, e.getMessage()); }
    }

    @Override
    public void updateForSavePolicy(Model model) {
        try { delegate.updateForSavePolicy(model); } catch (Exception e) { LOGGER.warn(WARN_MSG, e.getMessage()); }
    }

    @Override
    public void updateForAddPolicies(String sec, String ptype, List<List<String>> rules) {
        try { delegate.updateForAddPolicies(sec, ptype, rules); } catch (Exception e) { LOGGER.warn(WARN_MSG, e.getMessage()); }
    }

    @Override
    public void updateForRemovePolicies(String sec, String ptype, List<List<String>> rules) {
        try { delegate.updateForRemovePolicies(sec, ptype, rules); } catch (Exception e) { LOGGER.warn(WARN_MSG, e.getMessage()); }
    }
}
