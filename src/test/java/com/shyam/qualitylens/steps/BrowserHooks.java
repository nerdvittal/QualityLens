package com.shyam.qualitylens.steps;

import io.cucumber.java.After;
import io.cucumber.java.Before;

public class BrowserHooks {
    private final BrowserSession browserSession;

    public BrowserHooks(BrowserSession browserSession) {
        this.browserSession = browserSession;
    }

    @Before
    public void startBrowser() {
        browserSession.startChrome();
    }

    @After
    public void closeBrowser() {
        browserSession.close();
    }
}
