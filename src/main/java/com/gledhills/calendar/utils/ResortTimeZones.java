package com.gledhills.calendar.utils;

import java.time.ZoneId;
import java.util.Map;

/**
 * Which time zone each resort's clock runs on.
 *
 * <p>Needed the moment a shift is described to anything outside this site. "8:00" on a page is
 * read by a person standing at the mountain; the same shift sent to somebody's Outlook or Google
 * calendar has to say <em>which</em> 8:00, or it lands hours out for every patroller.
 *
 * <p><b>These are defaults, not authority.</b> They were worked out from each resort's name and the
 * website in {@code PatrolData.resortMap}, which is good evidence but not the resort's own answer.
 * The intended shape is that a director confirms or corrects it on Web Site Preferences and that
 * choice wins; this table is what a resort starts with and what it falls back to.
 *
 * <p>Deliberately only the four continental zones rather than the finer names. {@code America/Boise}
 * and {@code America/Denver} keep the same clock, as do {@code America/Menominee} and
 * {@code America/Chicago} - so choosing the broader name cannot be wrong by an hour, and the only
 * mistake that matters is putting a resort in the wrong zone altogether.
 */
public final class ResortTimeZones {

    /** Where the site itself is, and what an unknown resort falls back to. */
    public static final ZoneId DEFAULT = ZoneId.of("America/Denver");

    private static final String MOUNTAIN = "America/Denver";
    private static final String CENTRAL  = "America/Chicago";
    private static final String EASTERN  = "America/New_York";
    private static final String PACIFIC  = "America/Los_Angeles";

    /**
     * Resort to zone.
     *
     * <p>Every town here was either settled by the resort's own website or confirmed by the site
     * owner. A director can still change their own on Web Site Preferences, which is what actually
     * decides it - this table is the default a resort starts from.
     */
    private static final Map<String, String> BY_RESORT = Map.ofEntries(
        //--- Mountain ---------------------------------------------------------------------
        Map.entry("AntelopeButte",     MOUNTAIN),   //Wyoming, Bighorn Mountains
        Map.entry("BigHorn",           MOUNTAIN),   //Meadowlark, Wyoming
        Map.entry("Brighton",          MOUNTAIN),   //Utah
        Map.entry("CasperMountain",    MOUNTAIN),   //Wyoming
        Map.entry("GrandTarghee",      MOUNTAIN),   //Alta, Wyoming
        Map.entry("GrandTargheeHosts", MOUNTAIN),   //same mountain
        Map.entry("GreatDivide",       MOUNTAIN),   //Montana
        Map.entry("Hesperus",          MOUNTAIN),   //Colorado
        Map.entry("IFNordic",          MOUNTAIN),   //Idaho Falls, southern Idaho
        Map.entry("KellyCanyon",       MOUNTAIN),   //Ririe, southern Idaho
        Map.entry("MagicMountain",     MOUNTAIN),   //Kimberly, Idaho
        Map.entry("psiaMagicMountain", MOUNTAIN),   //Kimberly, Idaho - same mountain
        Map.entry("PineCreek",         MOUNTAIN),   //Montpelier, Idaho
        Map.entry("Pomerelle",         MOUNTAIN),   //Albion, southern Idaho
        Map.entry("Snowbowl",          MOUNTAIN),   //Missoula, Montana
        Map.entry("SnowKing",          MOUNTAIN),   //Jackson, Wyoming
        Map.entry("SoldierMountain",   MOUNTAIN),   //Fairfield, southern Idaho
        //The demo resorts get a zone so nothing has a hole in it, but they will never produce a
        //calendar feed: their members are invented, exactly as they are never emailed. See
        //PatrolData.isDemoResort, which is the one test for that.
        Map.entry("Sample",            MOUNTAIN),   //the demo; the site's own zone
        Map.entry("SampleBackup",      MOUNTAIN),

        //--- Central ----------------------------------------------------------------------
        Map.entry("Afton",             CENTRAL),    //Afton Alps, Minnesota
        Map.entry("Andes",             CENTRAL),    //Andes Tower Hills, Minnesota
        Map.entry("BlackjackMountain", CENTRAL),    //Snow River, Gogebic County Michigan - Central
        Map.entry("BuenaVista",        CENTRAL),    //Bemidji, Minnesota
        Map.entry("CoffeeMill",        CENTRAL),    //Wabasha, Minnesota
        Map.entry("DetroitMountain",   CENTRAL),    //Detroit Lakes, Minnesota
        Map.entry("DevilsHead",        CENTRAL),    //Merrimac, Wisconsin
        Map.entry("HylandHills",       CENTRAL),    //Bloomington, Minnesota
        Map.entry("IndianHeadMountain", CENTRAL),   //Snow River, Gogebic County Michigan
        Map.entry("MountKato",         CENTRAL),    //Mankato, Minnesota
        Map.entry("NorwayMountain",    CENTRAL),    //Norway, Michigan - Central counties
        Map.entry("PaidSnowCreek",     CENTRAL),    //Weston, Missouri
        Map.entry("PaulBunyan",        CENTRAL),    //Bemidji, Minnesota
        Map.entry("PineMountain",      CENTRAL),    //Iron Mountain, Michigan - Central counties
        Map.entry("psiaPineMountain",  CENTRAL),    //same mountain
        Map.entry("SnowCreek",         CENTRAL),    //Weston, Missouri
        Map.entry("Steeplechase",      CENTRAL),    //Mazeppa, Minnesota

        //--- Eastern ----------------------------------------------------------------------
        Map.entry("BlackMountain",     EASTERN),    //New Hampshire
        Map.entry("HermonMountain",    EASTERN),    //Maine
        Map.entry("HolidayMountain",   EASTERN),    //Monticello, New York
        Map.entry("LonesomePine",      EASTERN),    //Fort Kent, Maine
        Map.entry("MountPleasant",     EASTERN),    //Cambridge Springs, Pennsylvania
        Map.entry("Plattekill",        EASTERN),    //New York
        Map.entry("RMSP",              EASTERN),    //Ragged Mountain, New Hampshire

        //--- Pacific ----------------------------------------------------------------------
        Map.entry("LeeCanyon",         PACIFIC),    //Las Vegas, Nevada
        Map.entry("MtBaldy",           PACIFIC)     //California
    );

    private ResortTimeZones() {
    }

    /**
     * This resort's zone, or {@link #DEFAULT} for one not listed.
     *
     * <p>Falls back rather than throwing: a resort added to {@code resortMap} and not to this table
     * should show times an hour or two out at worst, not break the page it is on.
     */
    public static ZoneId forResort(String resort) {
        String zone = resort == null ? null : BY_RESORT.get(resort.trim());
        return zone == null ? DEFAULT : ZoneId.of(zone);
    }

    /**
     * The zone actually in force: the director's answer when they have given one, this table
     * otherwise.
     *
     * <p>This is the method anything showing a time to the outside world should call. The table is
     * a starting point; Web Site Preferences is where a resort says what is true.
     *
     * @param settings this resort's director settings, or null when they cannot be read
     */
    public static ZoneId inForce(String resort, com.gledhills.calendar.store.DirectorSettings settings) {
        if (settings != null) {
            String chosen = settings.getTimeZone();
            if (chosen != null && !chosen.isEmpty()) {
                try {
                    return ZoneId.of(chosen);
                }
                catch (Exception e) {
                    //a stored value that is not a zone must not break every page that reads it
                }
            }
        }
        return forResort(resort);
    }

    /** Whether this resort has a zone of its own rather than falling back. */
    public static boolean isKnown(String resort) {
        return resort != null && BY_RESORT.containsKey(resort.trim());
    }

    /** What the Web Site Preferences dropdown offers, in the order it shows them. */
    public static final java.util.List<String> CHOICES = java.util.List.of(
        EASTERN, CENTRAL, MOUNTAIN, PACIFIC, "America/Anchorage", "Pacific/Honolulu",
        "America/Phoenix");

    /** A zone as a director would recognise it: "Mountain - America/Denver". */
    public static String label(String zone) {
        return switch (zone) {
            case EASTERN -> "Eastern";
            case CENTRAL -> "Central";
            case MOUNTAIN -> "Mountain";
            case PACIFIC -> "Pacific";
            case "America/Anchorage" -> "Alaska";
            case "Pacific/Honolulu" -> "Hawaii";
            case "America/Phoenix" -> "Arizona (no daylight saving)";
            default -> zone;
        } + " - " + zone;
    }

    /** The resorts this table covers, for tests and for the page that lets a director edit it. */
    public static Map<String, String> all() {
        return BY_RESORT;
    }
}
