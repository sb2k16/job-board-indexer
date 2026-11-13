/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import io.github.sb2k16.jobboard.model.Tenant;

import java.util.ArrayList;
import java.util.List;

/** One platform in a pipeline group: which adapter, where, and which boards. */
public class PlatformConfig {

    @JsonProperty("name")
    @NotEmpty
    private String name;

    @JsonProperty("adapter")
    private String adapter;

    /** Path to a platform spec file (class A); when set, the spec engine runs this platform. */
    @JsonProperty("spec")
    private String spec;

    /** Overrides the spec's base_url (staging mirrors, local fixtures). */
    @JsonProperty("base_url")
    private String baseUrl;

    @JsonProperty("tenants")
    private List<Tenant> tenants = new ArrayList<>();

    @JsonProperty("tenants_file")
    private String tenantsFile;

    public PlatformConfig() {
    }

    public PlatformConfig(final String name, final String adapter, final String baseUrl, final List<Tenant> tenants) {
        this.name = name;
        this.adapter = adapter;
        this.baseUrl = baseUrl;
        this.tenants = tenants;
    }

    public String getName() {
        return name;
    }

    /** Adapter name for a platform without a spec (class C); defaults to the platform name. */
    public String getAdapter() {
        return adapter != null ? adapter : name;
    }

    public String getSpec() {
        return spec;
    }

    public PlatformConfig setSpec(final String spec) {
        this.spec = spec;
        return this;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public List<Tenant> getTenants() {
        return tenants;
    }

    public String getTenantsFile() {
        return tenantsFile;
    }
}
