package com.shyam.qualitylens.pages;

import com.shyam.qualitylens.steps.BrowserSession;
import java.net.URI;
import java.net.URISyntaxException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.PageFactory;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class LoginPage {
    private static final String DEFAULT_BASE_URL = "https://www.saucedemo.com/";
    private static final String BASE_URL_ENVIRONMENT_VARIABLE = "QUALITYLENS_BASE_URL";

    private final BrowserSession browserSession;
    private final String baseUrl;

    private WebDriver driver;
    private boolean elementsInitialized;

    @FindBy(id = "user-name")
    private WebElement usernameField;

    @FindBy(id = "password")
    private WebElement passwordField;

    @FindBy(id = "login-button")
    private WebElement loginButton;

    @FindBy(css = ".title")
    private WebElement productsTitle;

    public LoginPage(BrowserSession browserSession) {
        this.browserSession = browserSession;
        this.baseUrl = resolveBaseUrl(System.getenv(BASE_URL_ENVIRONMENT_VARIABLE));
    }

    private static String resolveBaseUrl(String configuredBaseUrl) {
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }

        String candidate = configuredBaseUrl.trim();
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            int port = uri.getPort();

            if (!uri.isAbsolute()
                    || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null
                    || (port != -1 && (port < 1 || port > 65535))) {
                throw new IllegalArgumentException(
                        "Invalid " + BASE_URL_ENVIRONMENT_VARIABLE
                                + ": expected an absolute HTTP or HTTPS URL with a valid host.");
            }

            return candidate;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(
                    "Invalid " + BASE_URL_ENVIRONMENT_VARIABLE
                            + ": expected an absolute HTTP or HTTPS URL with a valid host.",
                    exception
            );
        }
    }

    private synchronized void initializeElements() {
        if (elementsInitialized) {
            return;
        }

        WebDriver activeDriver = browserSession.getDriver();
        PageFactory.initElements(activeDriver, this);
        driver = activeDriver;
        elementsInitialized = true;
    }

    public void openLoginPage() {
        initializeElements();
        driver.get(baseUrl);
    }

    public void verifyLoginPageIsDisplayed() {
        initializeElements();

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        wait.until(ExpectedConditions.visibilityOf(usernameField));
    }

    public void logIn(String username, String password) {
        initializeElements();

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        wait.until(ExpectedConditions.visibilityOf(usernameField)).sendKeys(username);
        wait.until(ExpectedConditions.visibilityOf(passwordField)).sendKeys(password);
        wait.until(ExpectedConditions.elementToBeClickable(loginButton)).click();
    }

    public void verifyProductsPageIsDisplayed() {
        initializeElements();

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        String actualTitle = wait.until(ExpectedConditions.visibilityOf(productsTitle)).getText();
        if (!"Products".equals(actualTitle)) {
            throw new AssertionError(
                    "Expected Products page title to be 'Products' but was '" + actualTitle + "'.");
        }
    }
}
