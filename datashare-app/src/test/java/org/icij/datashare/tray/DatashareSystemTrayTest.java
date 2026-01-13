package org.icij.datashare.tray;

import dorkbox.systemTray.Menu;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.SystemTray;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import java.awt.event.ActionListener;
import java.net.URL;

import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class DatashareSystemTrayTest {
    @Mock private TrayActions trayActions;
    @Mock private SystemTray systemTray;
    @Mock private Menu menu;
    private DatashareSystemTray datashareSystemTray;

    @Before
    public void setUp() {
        initMocks(this);
        when(systemTray.getMenu()).thenReturn(menu);
        datashareSystemTray = new DatashareSystemTray(systemTray, trayActions);
    }

    @Test
    public void test_set_icon() {
        verify(systemTray).setImage(any(URL.class));
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
