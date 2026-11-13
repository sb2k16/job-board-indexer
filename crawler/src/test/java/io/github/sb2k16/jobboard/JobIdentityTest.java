/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard;

import org.junit.jupiter.api.Test;
import io.github.sb2k16.jobboard.model.JobRecord;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/** Fixtures are the measured pairs and boards from phase0/dedup-test.md and the Phase 0 samples. */
class JobIdentityTest {

    private static JobRecord job(final String company, final String title, final String location, final String req) {
        return new JobRecord().setCompany(company).setTitle(title).setLocation(location).setRequisitionId(req);
    }

    @Test
    void jobKey_crossPlatformPairsFromPhase0_matchOnRequisitionAndTitle() {
        assertThat(JobIdentity.jobKey(job("CVS Health", "Pharmacy Intern - Grad", "Ashburn, Virginia, United States", "R0996880"), true),
                equalTo(JobIdentity.jobKey(job("CVS Health", "Pharmacy Intern - Grad", "10563 - Virginia CVS Pharmacy, L.L.C.", "R0996880"), true)));
        assertThat(JobIdentity.jobKey(job("Dollar Tree", "Customer Service Associate",
                        "Ashburn, Virginia, United States of America", "R-256821"), true),
                equalTo(JobIdentity.jobKey(job("Dollar Tree", "Customer Service Associate", "VA-Ashburn", "R-256821"), true)));
        assertThat(JobIdentity.jobKey(job("CoStar", "Director, Pre-Sales Engineering",
                        "Irvine, California, United States of America", "R40220"), true),
                equalTo(JobIdentity.jobKey(job("CoStar", "Director, Pre-Sales Engineering", "Irvine (US)", "R40220"), true)));
    }

    @Test
    void jobKey_sharedRequisitionDifferentTitle_staysSeparate() {
        assertThat(JobIdentity.jobKey(job("The Boring Company", "Integration Engineer", "Bastrop, United States", "R100"), true),
                not(equalTo(JobIdentity.jobKey(job("The Boring Company", "Manufacturing Engineer", "Bastrop, United States", "R100"), true))));
    }

    @Test
    void jobKey_withoutRequisition_usesCanonicalLocation() {
        assertThat(JobIdentity.jobKey(job("Dollar Tree", "Customer Service Associate", "Ashburn, Virginia, United States of America", null), false),
                equalTo(JobIdentity.jobKey(job("Dollar Tree", "Customer Service Associate", "VA-Ashburn", null), false)));
        assertThat(JobIdentity.jobKey(job("Acme", "Engineer", "Austin, TX", null), false),
                not(equalTo(JobIdentity.jobKey(job("Acme", "Engineer", "Dallas, TX", null), false))));
    }

    @Test
    void jobKey_placeholderRequisition_fallsBackToLocationRule() {
        final JobRecord dublin = job("Stripe", "Abuse Investigator", "Dublin", "See Opening ID");
        final JobRecord seattle = job("Stripe", "Abuse Investigator", "Seattle, San Francisco, New York City", "See Opening ID");
        assertThat(JobIdentity.jobKey(dublin, true), not(equalTo(JobIdentity.jobKey(seattle, true))));
    }

    @Test
    void boardHasUsableRequisitionIds_stripePlaceholder_false() {
        final List<JobRecord> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            jobs.add(job("Stripe", "Role " + i, "Dublin", "See Opening ID"));
        }
        assertThat(JobIdentity.boardHasUsableRequisitionIds(jobs), equalTo(false));
    }

    @Test
    void boardHasUsableRequisitionIds_tenantWideConstantWithDigit_false() {
        final List<JobRecord> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            jobs.add(job("X", "Role " + i, "Dublin", "REQ-1"));
        }
        assertThat(JobIdentity.boardHasUsableRequisitionIds(jobs), equalTo(false));
    }

    @Test
    void boardHasUsableRequisitionIds_mostlyDistinct_true() {
        final List<JobRecord> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            jobs.add(job("Gem", "Role " + i, "Remote", i < 2 ? "R100" : "R" + i));
        }
        assertThat(JobIdentity.boardHasUsableRequisitionIds(jobs), equalTo(true));
        assertThat(JobIdentity.boardHasUsableRequisitionIds(List.of()), equalTo(false));
    }

    @Test
    void locationToken_structuredAndFreeText_agree() {
        assertThat(JobIdentity.locationToken(new JobRecord().setCity("Ashburn").setRegion("VA").setCountry("US")), equalTo("ashburn|va"));
        assertThat(JobIdentity.locationToken(new JobRecord().setLocation("Ashburn, Virginia, United States")), equalTo("ashburn|va"));
        assertThat(JobIdentity.locationToken(new JobRecord().setLocation("VA-Ashburn")), equalTo("ashburn|va"));
        assertThat(JobIdentity.locationToken(new JobRecord().setCity("Zürich").setCountry("Switzerland")), equalTo("zurich|switzerland"));
        assertThat(JobIdentity.locationToken(new JobRecord().setCountry("United States of America")), equalTo("us"));
        assertThat(JobIdentity.locationToken(new JobRecord()), equalTo(""));
    }

    @Test
    void contentHash_changesWithDescriptionOnly_notWithWhitespace() {
        final JobRecord a = job("A", "T", "L", null).setDescription("Build things.");
        final JobRecord b = job("A", "T", "L", null).setDescription("Build   things.\n");
        final JobRecord c = job("A", "T", "L", null).setDescription("Build other things.");
        assertThat(JobIdentity.contentHash(a), equalTo(JobIdentity.contentHash(b)));
        assertThat(JobIdentity.contentHash(a), not(equalTo(JobIdentity.contentHash(c))));
    }

    @Test
    void normalize_foldsCaseAccentsAndPunctuation() {
        assertThat(JobIdentity.normalize("  Café-Owner, Sr. "), equalTo("cafe owner sr"));
        assertThat(JobIdentity.normalize(null), equalTo(""));
    }

    @Test
    void jobKey_bareCityLocations_staySeparateAcrossCities() {
        // Regression from the T1.2 end-to-end run: comma-less locations produced an empty token, so the
        // same title in Dublin and London (and 7 cities for "Software Engineer, New Grad") became one doc.
        assertThat(JobIdentity.jobKey(job("Stripe", "Senior Staff Product Manager, Risk", "Dublin", null), false),
                not(equalTo(JobIdentity.jobKey(job("Stripe", "Senior Staff Product Manager, Risk", "London", null), false))));
        assertThat(JobIdentity.jobKey(job("Stripe", "People Consultant", "Toronto", null), false),
                not(equalTo(JobIdentity.jobKey(job("Stripe", "People Consultant", "Chicago", null), false))));
        assertThat(JobIdentity.jobKey(job("Stripe", "Abuse Investigator", "Dublin", null), false),
                equalTo(JobIdentity.jobKey(job("Stripe", "Abuse Investigator", " dublin ", null), false)));
    }

    @Test
    void locationToken_structuredCountryWithBareCityString_keepsCity() {
        final JobRecord sf = job("Acme", "Engineer", "San Francisco", null).setCountry("United States");
        final JobRecord nyc = job("Acme", "Engineer", "New York", null).setCountry("United States");
        assertThat(JobIdentity.locationToken(sf), equalTo("san francisco|us"));
        assertThat(JobIdentity.locationToken(sf), not(equalTo(JobIdentity.locationToken(nyc))));
    }
}
