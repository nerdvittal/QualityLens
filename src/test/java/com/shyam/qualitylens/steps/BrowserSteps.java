package com.shyam.qualitylens.steps;

import com.shyam.qualitylens.pages.LoginPage;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class BrowserSteps {
    private final LoginPage loginPage;

    public BrowserSteps(LoginPage loginPage) {
        this.loginPage = loginPage;
    }

    @Given("I open the SauceDemo home page")
    public void openSauceDemoHomePage() {
        loginPage.openLoginPage();
    }

    @When("I log in with username {string} and password {string}")
    public void logIn(String username, String password) {
        loginPage.logIn(username, password);
    }

    @Then("the SauceDemo home page is displayed")
    public void verifySauceDemoHomePageIsDisplayed() {
        loginPage.verifyLoginPageIsDisplayed();
    }

    @Then("the Products page is displayed")
    public void verifyProductsPageIsDisplayed() {
        loginPage.verifyProductsPageIsDisplayed();
    }
}
