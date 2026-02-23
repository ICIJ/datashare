package org.icij.datashare.policies;

import org.casbin.jcasbin.persist.Adapter;
import org.casbin.jcasbin.persist.BatchAdapter;
import org.casbin.jcasbin.persist.FilteredAdapter;
import org.casbin.jcasbin.persist.UpdatableAdapter;

public interface CasbinRuleAdapter extends Adapter, BatchAdapter, UpdatableAdapter, FilteredAdapter {
}
