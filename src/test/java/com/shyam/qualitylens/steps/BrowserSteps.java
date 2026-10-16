package com.shyam.qualitylens.steps;

import com.shyam.qualitylens.pages.LoginPage;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

public class BrowserSteps {
    private final LoginPage loginPage;

    public BrowserSteps(LoginPage loginPage) {
        this.loginPage = loginPage;
    }

    @Given("I open the SauceDemo home page")
    public void openSauceDemoHomePage() {
        loginPage.openLoginPage();
    }

    @Then("the SauceDemo home page is displayed")
    public void verifySauceDemoHomePageIsDisplayed() {
        loginPage.verifyLoginPageIsDisplayed();
    }
}
