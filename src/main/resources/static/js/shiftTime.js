/*
 * The browser half of "a Start Time has to be a time".
 *
 * Needs dialogs.js, which must be loaded first - the messages here go through its message box.
 *
 * Deliberately the same rules as ShiftTime.java, including the awkward ones: a bare number is a
 * 24 hour clock, a meridiem is a 12 hour clock where 12am is midnight, and the match is anchored
 * so a label like "PMExtra" is not mistaken for an afternoon.
 *
 * This is a courtesy, not a guard. The form can be posted around it, so StartAndLabel on the
 * server has the last word and applies the same rule again.
 */

function buildShiftTime(hour, minute, meridiem) {
    if (isNaN(hour) || isNaN(minute) || minute < 0 || minute > 59) {
        return null;
    }
    if (meridiem) {
        if (hour < 1 || hour > 12) {
            return null;
        }
        var pm = meridiem.toLowerCase() === 'p';
        if (hour === 12) {
            hour = pm ? 12 : 0;
        } else if (pm) {
            hour += 12;
        }
    } else if (hour < 0 || hour > 23) {
        return null;
    }
    return hour + ':' + (minute < 10 ? '0' : '') + minute;
}

/** The canonical form of a time, or null when this is not a time at all. */
function normalizeShiftTime(raw) {
    if (raw === null || raw === undefined) {
        return null;
    }
    var text = String(raw).trim();
    if (text === '') {
        return null;
    }
    var found;
    if ((found = /^(\d{1,2})\s*:\s*(\d{2})\s*(?:([ap])\.?m\.?)?$/i.exec(text))) {
        return buildShiftTime(parseInt(found[1], 10), parseInt(found[2], 10), found[3]);
    }
    if ((found = /^(\d{1,2})\s*([ap])\.?m\.?$/i.exec(text))) {
        return buildShiftTime(parseInt(found[1], 10), 0, found[2]);
    }
    if ((found = /^(\d{1,2})$/.exec(text))) {
        return buildShiftTime(parseInt(found[1], 10), 0, null);
    }
    if ((found = /^(\d{3,4})$/.exec(text))) {
        var digits = found[1];
        var split = digits.length - 2;
        return buildShiftTime(parseInt(digits.slice(0, split), 10),
                              parseInt(digits.slice(split), 10), null);
    }
    return null;
}

/**
 * Checked as the director leaves the Start Time box. A recognised time is rewritten in its
 * canonical form there and then, so what will be stored is what they can see.
 */
function checkStartTime(field) {
    var typed = field.value.trim();
    if (typed === '') {
        return true;      //blank is allowed: the slot then shows its Label, or nothing
    }
    var canonical = normalizeShiftTime(typed);
    if (canonical === null) {
        showMessageDialog('"' + typed + '" is not a time.\n\n'
              + 'Start Time takes a time like 8:00, 8am, 16 or 16:30.\n\n'
              + 'If this is a name for the shift - "TL", "dir", "mentee" - put it in the Label box '
              + 'to its left. The calendar shows the Label when there is one, and the Start Time '
              + 'when there is not.',
              function () {
                  field.focus();
                  field.select();
              },
              'That is not a time');
        return false;
    }
    field.value = canonical;
    return true;
}

/**
 * Checked as the director leaves the End Time box.
 *
 * <p>Unlike Start Time, an End Time that has not been touched is left alone whatever it says:
 * resorts have years of End values that were never times - "end time", "5:30-9", the literal
 * "empty" - and there is nowhere to move them to. defaultValue is what the server rendered into
 * the field, so comparing against it is what tells an edit from an untouched historical value.
 * The server applies the same rule against the stored row, which is the copy that counts.
 */
function checkEndTime(field) {
    var typed = field.value.trim();
    if (typed === field.defaultValue.trim()) {
        return true;      //untouched: history stays as it is
    }
    if (typed === '') {
        return true;      //clearing it is allowed; End Time is not required
    }
    var canonical = normalizeShiftTime(typed);
    if (canonical === null) {
        showMessageDialog('"' + typed + '" is not a time.\n\n'
              + 'End Time takes a time like 8:00, 8am, 16 or 16:30.\n\n'
              + 'Older shifts may still show something else here. Those are left as they are, but '
              + 'anything you change has to be a real time so shifts can sync to a personal '
              + 'calendar.',
              function () {
                  field.focus();
                  field.select();
              },
              'That is not a time');
        return false;
    }
    field.value = canonical;
    return true;
}

/** Every Start Time and every changed End Time on the form, checked before it is submitted. */
function checkAllStartTimes(form) {
    var fields = form.querySelectorAll('input.shift-start-time');
    for (var i = 0; i < fields.length; ++i) {
        if (!checkStartTime(fields[i])) {
            return false;
        }
    }
    var ends = form.querySelectorAll('input.shift-end-time');
    for (var j = 0; j < ends.length; ++j) {
        if (!checkEndTime(ends[j])) {
            return false;
        }
    }
    return true;
}

/**
 * Every row must carry a Label or a Start Time.
 *
 * A slot is drawn as its label, or failing that its start time, so a row with neither is a blank
 * line on the calendar for a shift somebody is meant to work. The rows are paired by the number on
 * the end of the field name rather than by position, so a hidden or reordered row cannot pair the
 * wrong two boxes together.
 */
function checkLabelOrStartTime(form) {
    var starts = form.querySelectorAll('input.shift-start-time');
    for (var i = 0; i < starts.length; ++i) {
        var suffix = starts[i].name.substring(starts[i].name.indexOf('_') + 1);
        var label = form.querySelector('[name="label_' + suffix + '"]');
        var labelText = label ? label.value.trim() : '';

        // A shift with no patroller slots is nobody's to turn up for, so it cannot leave a blank
        // line on the calendar - which is the only thing this rule is protecting against. A
        // director laying a day out needs to be able to leave such a row empty. The server
        // applies the same exemption.
        var count = form.querySelector('[name="count_' + suffix + '"]');
        if (count && count.value.trim() === '0') {
            continue;
        }

        if (starts[i].value.trim() === '' && labelText === '') {
            var box = label || starts[i];
            showMessageDialog('Shift ' + (i + 1) + ' has no Label and no Start Time.\n\n'
                  + 'Every shift needs one or the other. The calendar shows the Label if there is '
                  + 'one, and the Start Time if there is not.',
                  function () {
                      box.focus();
                  },
                  'This shift needs a Label or a Start Time');
            return false;
        }
    }
    return true;
}
