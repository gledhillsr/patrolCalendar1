package com.gledhills.calendar.store;

import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.SqlWriter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A custom menu button a resort has configured for its own header.
 *
 * Brighton's header carries two hand-written buttons - "Volunteer Application" and "Weather" -
 * which is why there are two header fragments in the first place. This table lets every resort
 * define the same thing for itself, so those can eventually move here and leave one header.
 *
 * Four buttons per resort, held at fixed positions 1..{@link #MAX_BUTTONS}: the maintenance page
 * shows four rows whether or not they are filled in, so a position is a slot rather than a list
 * entry and nothing renumbers when a director clears the middle one.
 *
 * Unlike {@link ShiftTemplatePatrollers} and {@link PasswordResetToken}, this table is <b>not</b>
 * created on first use - "Site Wide Configuration" on the Directors page creates it across every
 * resort in one go, which keeps a CREATE off every read and write. A resort whose table is missing
 * reads as "no custom buttons" and cannot save one, so **a resort added to
 * {@code PatrolData.resortMap} needs that job run again** before its directors can configure a
 * menu.
 *
 * @author Steve Gledhill
 */
public class MenuButton {

  /** How many buttons a resort may define. The maintenance page draws exactly this many rows. */
  public static final int MAX_BUTTONS = 4;

  /**
   * How long a button's label may be. The column holds 40, but a menu bar has finite room and a
   * long label wraps the whole row - 20 is about what fits beside the built-in items.
   */
  public static final int MAX_TEXT_LENGTH = 20;

  private static final String TABLE = "menubuttons";

  private final int position;
  private final String text;
  private final String url;
  private final boolean memberOnly;
  private final boolean enabled;

  public MenuButton(int position, String text, String url, boolean memberOnly, boolean enabled) {
    this.position = position;
    this.text = text == null ? "" : text.trim();
    this.url = url == null ? "" : url.trim();
    this.memberOnly = memberOnly;
    this.enabled = enabled;
  }

  /** An unfilled slot, so the maintenance page always has {@link #MAX_BUTTONS} rows to draw. */
  public static MenuButton empty(int position) {
    return new MenuButton(position, "", "", false, false);
  }

  public int getPosition() {
    return position;
  }

  public String getText() {
    return text;
  }

  public String getUrl() {
    return url;
  }

  public boolean isMemberOnly() {
    return memberOnly;
  }

  public boolean isEnabled() {
    return enabled;
  }

  /** Nothing filled in - the director has left this slot alone. */
  public boolean isBlank() {
    return text.isEmpty() && url.isEmpty();
  }

  /**
   * Whether this button should be drawn in the header. Enabled is the director's switch; the text
   * and url are what it needs to be a button at all, so a half-filled slot stays hidden rather
   * than rendering as an empty link.
   */
  public boolean isDisplayable() {
    return enabled && !text.isEmpty() && !url.isEmpty();
  }

  /**
   * All {@link #MAX_BUTTONS} slots in position order, blanks included, so callers can index them
   * without a bounds check. Returns blanks throughout when the table is unavailable.
   */
  public static List<MenuButton> readAll(Logger LOG, Connection connection) {
    List<MenuButton> buttons = new ArrayList<>();
    for (int position = 1; position <= MAX_BUTTONS; position++) {
      buttons.add(empty(position));
    }

    String sql = "SELECT Position, ButtonText, Url, MemberOnly, Enabled FROM " + TABLE
      + " ORDER BY Position ASC";
    try {
      LOG.logSqlStatement(sql);
      PreparedStatement statement = connection.prepareStatement(sql);
      ResultSet results = statement.executeQuery();

      while (results.next()) {
        int position = results.getInt("Position");
        if (position < 1 || position > MAX_BUTTONS) {
          LOG.error("ignoring menu button at out of range position " + position);
          continue;
        }
        buttons.set(position - 1, new MenuButton(position,
                                                 results.getString("ButtonText"),
                                                 results.getString("Url"),
                                                 results.getInt("MemberOnly") == 1,
                                                 results.getInt("Enabled") == 1));
      }
      results.close();
      statement.close();
    }
    catch (SQLException e) {
      LOG.logException("reading " + TABLE, e);
    }
    return buttons;
  }

  /** Only the ones the header should draw, in position order. */
  public static List<MenuButton> readDisplayable(Logger LOG, Connection connection) {
    List<MenuButton> shown = new ArrayList<>();
    for (MenuButton button : readAll(LOG, connection)) {
      if (button.isDisplayable()) {
        shown.add(button);
      }
    }
    return shown;
  }

  /**
   * Replace every slot with what was passed. A blank slot is deleted rather than stored, so the
   * table only ever holds buttons somebody meant to define.
   */
  public static boolean writeAll(Logger LOG, Connection connection, List<MenuButton> buttons) {
    boolean allWritten = true;
    for (MenuButton button : buttons) {
      allWritten &= button.isBlank() ? delete(LOG, connection, button.getPosition())
                                     : write(LOG, connection, button);
    }
    return allWritten;
  }

  private static boolean write(Logger LOG, Connection connection, MenuButton button) {
    String sql = "REPLACE INTO " + TABLE
      + " (Position, ButtonText, Url, MemberOnly, Enabled) VALUES ("
      + button.getPosition() + ", "
      + "'" + escape(button.getText()) + "', "
      + "'" + escape(button.getUrl()) + "', "
      + (button.isMemberOnly() ? 1 : 0) + ", "
      + (button.isEnabled() ? 1 : 0) + ")";
    try {
      SqlWriter.executeUpdate(LOG, connection, "writeMenuButton", sql);
      return true;
    }
    catch (SQLException e) {
      return false; //already logged by SqlWriter
    }
  }

  private static boolean delete(Logger LOG, Connection connection, int position) {
    String sql = "DELETE FROM " + TABLE + " WHERE Position = " + position;
    try {
      SqlWriter.executeUpdate(LOG, connection, "deleteMenuButton", sql);
      return true;
    }
    catch (SQLException e) {
      return false; //already logged by SqlWriter
    }
  }

  /**
   * Create the table for a resort up front, rather than waiting for somebody to open the page.
   *
   * Site Wide Configuration on the Directors page runs this across every resort. It is the only
   * caller: the read and write paths deliberately do not create anything, so this is what a newly
   * added resort needs before its menu can be configured.
   *
   * @return whether the table is now there
   */
  public static boolean createTable(Logger LOG, Connection connection) {
    return ensureTableExists(LOG, connection);
  }

  /**
   * Create the table if this resort has never used the feature. Returns false when the table is
   * unavailable, so callers degrade to "no custom buttons" rather than failing the page.
   */
  private static boolean ensureTableExists(Logger LOG, Connection connection) {
    String sql = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
      + "Position INT NOT NULL, "
      + "ButtonText VARCHAR(40) NOT NULL, "
      + "Url VARCHAR(255) NOT NULL, "
      + "MemberOnly TINYINT NOT NULL DEFAULT 0, "
      + "Enabled TINYINT NOT NULL DEFAULT 0, "
      + "PRIMARY KEY (Position)"
      + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    try {
      SqlWriter.executeUpdate(LOG, connection, "ensureMenuButtonsTable", sql);
      return true;
    }
    catch (SQLException e) {
      return false; //already logged by SqlWriter
    }
  }

  /** The rest of this codebase builds SQL by hand; at least keep quotes from breaking out. */
  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("'", "''");
  }
}
