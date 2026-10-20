
Feature: QualityLens Smoke Test

  Scenario: Verify the application is reachable
    Given I open the SauceDemo home page
    Then the SauceDemo home page is displayed

  Scenario: Verify successful login
    Given I open the SauceDemo home page
    When I log in with username "standard_user" and password "secret_sauce"
    Then the Products page is displayed