// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.processing;

import com.yahoo.searchdefinition.Search;
import com.yahoo.searchdefinition.SearchBuilder;
import com.yahoo.searchdefinition.SearchDefinitionTestCase;
import com.yahoo.searchdefinition.parser.ParseException;
import com.yahoo.vespa.documentmodel.DocumentSummary;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** @author bratseth */
public class WeightedSetSummaryToTestCase extends SearchDefinitionTestCase {

    @Test
    public void testRequireThatImplicitFieldsAreCreated() throws IOException, ParseException {
        Search search = SearchBuilder.buildFromFile("src/test/examples/weightedset-summaryto.sd");
        assertNotNull(search);
    }

}
