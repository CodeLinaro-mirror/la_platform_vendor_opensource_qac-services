/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.  
 * SPDX-License-Identifier: BSD-3-Clause-Clear 
 */

package com.qualcomm.qaior.screen_understanding.sample_app;

import java.util.List;

public class TestScenario {
    public static class Operation {
        public String op; // start, update, stop, delete, wait
        public int waitMs; // used when op == wait
    }

    private final String name;
    private final String description;
    private final List<Operation> operations;

    public TestScenario(String name, String description, List<Operation> operations) {
        this.name = name;
        this.description = description;
        this.operations = operations;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<Operation> getOperations() {
        return operations;
    }
}
