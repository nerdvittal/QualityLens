package com.shyam.qualitylens.steps;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;

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
    public void closeBrowser(Scenario scenario) {
        RuntimeException screenshotException = null;
        try {
            if (scenario.isFailed()) {
                byte[] screenshot = ((TakesScreenshot) browserSession.getDriver())
                        .getScreenshotAs(OutputType.BYTES);
                scenario.attach(screenshot, "image/png", "Failure screenshot");
            }
        } catch (RuntimeException exception) {
            screenshotException = exception;
        } finally {
            try {
                browserSession.close();
            } catch (RuntimeException cleanupException) {
                if (screenshotException == null) {
                    throw cleanupException;
                }
                if (screenshotException != cleanupException) {
                    screenshotException.addSuppressed(cleanupException);
                }
            }
        }
        if (screenshotException != null) {
            throw screenshotException;
        }
    }
}
