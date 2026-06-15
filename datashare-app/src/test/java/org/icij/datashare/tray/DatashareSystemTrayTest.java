package org.icij.datashare.tray;

import dorkbox.systemTray.Menu;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.SystemTray;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.awt.Image;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;

import static org.icij.datashare.tray.SystemThemeDetector.Theme;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class DatashareSystemTrayTest {
    @Mock private TrayActions trayActions;
    @Mock private SystemTray systemTray;
    @Mock private Menu menu;
    @Mock private TrayIconProvider iconProvider;
    private DatashareSystemTray datashareSystemTray;

    @Before
    public void setUp() {
        initMocks(this);
        when(systemTray.getMenu()).thenReturn(menu);
        when(iconProvider.loadInitialTrayImage(anyInt()))
                .thenReturn(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
        when(iconProvider.tracksSystemTheme()).thenReturn(false); // no background watcher in unit tests
        datashareSystemTray = new DatashareSystemTray(systemTray, trayActions, iconProvider);
    }

    @Test
    public void test_set_icon() {
        verify(systemTray).setImage(any(Image.class));
    }

    @Test
    public void test_falls_back_to_default_when_no_icon_image() {
        reset(systemTray);
        when(systemTray.getMenu()).thenReturn(menu);
        when(iconProvider.loadInitialTrayImage(anyInt())).thenReturn(null);

        new DatashareSystemTray(systemTray, trayActions, iconProvider);

        verify(systemTray).setImage(any(Image.class)); // default pink image
    }

    @Test
    public void test_refresh_applies_newly_detected_theme() {
        when(iconProvider.currentTheme()).thenReturn(Theme.DARK);
        when(iconProvider.loadTrayImage(anyInt(), eq(Theme.DARK)))
                .thenReturn(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));

        datashareSystemTray.refreshThemeIcon();

        verify(iconProvider).loadTrayImage(anyInt(), eq(Theme.DARK));
        verify(systemTray, times(2)).setImage(any(Image.class)); // initial + refreshed
    }

    @Test
    public void test_refresh_skips_setImage_when_theme_unchanged() {
        when(iconProvider.currentTheme()).thenReturn(Theme.DARK);
        when(iconProvider.loadTrayImage(anyInt(), eq(Theme.DARK)))
                .thenReturn(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));

        datashareSystemTray.refreshThemeIcon(); // applies DARK
        datashareSystemTray.refreshThemeIcon(); // same theme -> no extra setImage

        verify(systemTray, times(2)).setImage(any(Image.class)); // initial + first refresh only
    }

    @Test
    public void test_refresh_stops_watcher_when_theme_undetectable() {
        ScheduledExecutorService watcher = mock(ScheduledExecutorService.class);
        when(iconProvider.tracksSystemTheme()).thenReturn(true);
        when(iconProvider.currentTheme()).thenReturn(Theme.UNKNOWN);
        DatashareSystemTray tray =
                new DatashareSystemTray(systemTray, trayActions, iconProvider, watcher);
        reset(systemTray); // discard the initial icon set during construction

        tray.refreshThemeIcon();

        verify(watcher).shutdownNow();
        verify(systemTray, never()).setImage(any(Image.class)); // icon unchanged: still the fallback
    }

    @Test
    public void test_menu_items_are_added() {
        verify(menu, times(2)).add(any(MenuItem.class));
    }

    @Test
    public void test_open_browser_action() throws Exception {
        ArgumentCaptor<MenuItem> menuItemCaptor = ArgumentCaptor.forClass(MenuItem.class);
        verify(menu, times(2)).add(menuItemCaptor.capture());

        MenuItem openBrowserItem = menuItemCaptor.getAllValues().stream()
                .filter(item -> "Open Browser".equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new Exception("Menu item 'Open Browser' not found"));

        ActionListener actionListener = openBrowserItem.getCallback();
        actionListener.actionPerformed(null);

        verify(trayActions).openBrowser();
    }

    @Test
    public void test_quit_action() throws Exception {
        ArgumentCaptor<MenuItem> menuItemCaptor = ArgumentCaptor.forClass(MenuItem.class);
        verify(menu, times(2)).add(menuItemCaptor.capture());

        MenuItem quitItem = menuItemCaptor.getAllValues().stream()
                .filter(item -> "Quit".equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new Exception("Menu item 'Quit' not found"));

        ActionListener actionListener = quitItem.getCallback();
        actionListener.actionPerformed(null);

        verify(trayActions).quit();
    }

    @Test
    public void test_close_shuts_down_tray() throws Exception {
        datashareSystemTray.close();

        verify(systemTray).shutdown();
    }
}
