package com.gledhills.calendar.utils;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-resort time zone table.
 *
 * <p>These tests cannot tell you a resort is in the <em>right</em> zone - only a director can. What
 * they can do is stop the table drifting away from the resort list, and stop a typo becoming a
 * runtime failure on somebody's calendar feed.
 */
class ResortTimeZonesTest {

    /** A name {@code ZoneId} does not recognise would throw the first time a feed was built. */
    @Test
    void everyZoneNameIsReal() {
        for (Map.Entry<String, String> entry : ResortTimeZones.all().entrySet()) {
            ZoneId zone = ZoneId.of(entry.getValue());   //throws if the name is wrong
            assertTrue(zone.getId().startsWith("America/"),
                       entry.getKey() + " should be a continental zone, got " + zone);
        }
    }

    /**
     * Every resort in the map has a zone.
     *
     * <p>A resort added to {@code resortMap} and forgotten here falls back to Mountain, which is
     * silently wrong for anybody east or west of it - so this names them rather than leaving it to
     * be noticed on a patroller's phone.
     */
    @Test
    void everyResortInTheMapHasAZone() {
        List<String> missing = new ArrayList<>();
        for (String resort : new TreeSet<>(PatrolData.resortMap.keySet())) {
            if (!ResortTimeZones.isKnown(resort)) {
                missing.add(resort);
            }
        }
        assertEquals(List.of(), missing,
                     "these resorts have no time zone and would fall back to Mountain: " + missing);
    }

    /** And nothing here names a resort that no longer exists. */
    @Test
    void theTableNamesNoResortThatIsGone() {
        List<String> unknown = new ArrayList<>();
        for (String resort : ResortTimeZones.all().keySet()) {
            if (!PatrolData.isValidResort(resort)) {
                unknown.add(resort);
            }
        }
        assertEquals(List.of(), unknown,
                     "these are in the time zone table but not in resortMap: " + unknown);
    }

    /** A few the name and website settle beyond doubt, as a check on the obvious. */
    @Test
    void theUnambiguousOnesAreRight() {
        assertEquals("America/Denver", ResortTimeZones.forResort("Brighton").getId(), "Utah");
        assertEquals("America/Chicago", ResortTimeZones.forResort("Afton").getId(), "Minnesota");
        assertEquals("America/Los_Angeles", ResortTimeZones.forResort("LeeCanyon").getId(), "Nevada");
        assertEquals("America/New_York", ResortTimeZones.forResort("Plattekill").getId(), "New York");
    }

    /** Two names for one mountain must not end up in different zones. */
    @Test
    void resortsSharingAMountainShareAZone() {
        assertEquals(ResortTimeZones.forResort("GrandTarghee"),
                     ResortTimeZones.forResort("GrandTargheeHosts"));
        assertEquals(ResortTimeZones.forResort("PineMountain"),
                     ResortTimeZones.forResort("psiaPineMountain"));
        assertEquals(ResortTimeZones.forResort("MagicMountain"),
                     ResortTimeZones.forResort("psiaMagicMountain"));
        assertEquals(ResortTimeZones.forResort("SnowCreek"),
                     ResortTimeZones.forResort("PaidSnowCreek"));
        assertEquals(ResortTimeZones.forResort("Sample"),
                     ResortTimeZones.forResort("SampleBackup"));
    }

    /** An unknown resort answers rather than throwing: a wrong hour beats a broken page. */
    @Test
    void anUnknownResortFallsBack() {
        assertEquals(ResortTimeZones.DEFAULT, ResortTimeZones.forResort("NoSuchResort"));
        assertEquals(ResortTimeZones.DEFAULT, ResortTimeZones.forResort(null));
        assertEquals(ResortTimeZones.DEFAULT, ResortTimeZones.forResort(""));
        assertFalse(ResortTimeZones.isKnown("NoSuchResort"));
    }
}
