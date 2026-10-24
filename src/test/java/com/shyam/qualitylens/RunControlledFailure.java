package com.shyam.qualitylens;

import io.cucumber.junit.platform.engine.Constants;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("controlled-failure")
@ConfigurationParameter(
        key = Constants.GLUE_PROPERTY_NAME,
        value = "com.shyam.qualitylens.steps"
)
@ConfigurationParameter(
        key = Constants.PLUGIN_PROPERTY_NAME,
        value = "html:target/controlled-failure-report.html,json:target/controlled-first-run.json,rerun:target/controlled-first-run-rerun.txt"
)
public class RunControlledFailure {
}
