package org.icij.datashare.session;

import net.codestory.http.Cookie;
import net.codestory.http.Cookies;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Iterator;

public class SimpleCookies extends HashMap<String, Cookie> implements Cookies {
    @Override
    public Cookie get(String name) {
        return super.get(name);
    }

    @NotNull
    @Override
    public Iterator<Cookie> iterator() {
        return values().iterator();
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        return type.isInstance(HashMap.class) ? (T) this : null;
      }
}
