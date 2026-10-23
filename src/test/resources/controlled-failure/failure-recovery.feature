Feature: Controlled failure recovery

  Scenario: Controlled failure captures screenshot
    Given I open the SauceDemo home page
    Then the Products page is displayed

  Scenario: Execution continues after earlier scenario failure
    Given I open the SauceDemo home page
    Then the SauceDemo home page is displayed
