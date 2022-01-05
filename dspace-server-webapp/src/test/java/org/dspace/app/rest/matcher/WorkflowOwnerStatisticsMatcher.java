/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.matcher;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.is;

import org.hamcrest.Matcher;

/**
 * Utility class to provide convenient matchers for workflow owner statistics.
 */
public final class WorkflowOwnerStatisticsMatcher {

    private WorkflowOwnerStatisticsMatcher() {

    }

    public static Matcher<? super Object> match(String name, int count) {
        return allOf(hasJsonPath("$.name", is(name)),
            hasJsonPath("$.count", is(count)));
    }
}
