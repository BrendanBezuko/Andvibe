package com.example.andvibe

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.hamcrest.Matchers.containsString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: launch, visit each tab, run console `help`.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MainSmokeTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun visitTabsAndRunConsoleHelp() {
        val tabs = listOf(
            R.id.nav_board,
            R.id.nav_search,
            R.id.nav_files,
            R.id.nav_understand,
            R.id.nav_vibe,
            R.id.nav_build,
            R.id.nav_git,
        )
        for (id in tabs) {
            onView(withId(id)).perform(click())
            onView(withId(id)).check(matches(isDisplayed()))
        }

        onView(withId(R.id.openConsole)).perform(click())
        onView(withId(R.id.commandInput)).check(matches(isDisplayed()))
        onView(withId(R.id.commandInput)).perform(replaceText("help"))
        onView(withId(R.id.commandRun)).perform(click())
        onView(withId(R.id.logView)).check(matches(withText(containsString("AndVibe"))))
        onView(withId(R.id.logView)).check(matches(withText(containsString("help"))))
    }
}
