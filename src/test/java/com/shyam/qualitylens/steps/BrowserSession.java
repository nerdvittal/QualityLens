package com.shyam.qualitylens.steps;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;

public class BrowserSession {
    private WebDriver driver;

    public synchronized void startChrome() {
        if (driver == null) {
            driver = new ChromeDriver();
        }
    }

    public synchronized WebDriver getDriver() {
        if (driver == null) {
            throw new IllegalStateException(
                    "Chrome has not been started for this scenario.");
        }
        return driver;
    }

    public synchronized void close() {
        if (driver == null) {
            return;
        }

        try {
            driver.quit();
        } finally {
            driver = null;
        }
    }
}
