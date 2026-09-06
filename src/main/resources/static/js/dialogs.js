/*
 * The site's message and question boxes, in place of the browser's alert() and confirm().
 *
 * Loaded by any page that needs to say something or ask something. It carries no knowledge of
 * shifts: shiftTime.js builds its validation messages on top of this, and pages with nothing to do
 * with shifts - PurgeAssignments, Site Wide Configuration - use it directly.
 */

/*
 * A message box in the middle of the window, in place of the browser's alert().
 *
 * A native <dialog> is used because showModal() centres it and supplies the backdrop and the focus
 * trap; there is no positioning arithmetic here to get wrong. The element and its styles are built
 * on first use, so a page gets this by loading the script and nothing else.
 *
 * The text is set with textContent, never innerHTML: these messages quote back what the director
 * typed, and a shift label is a place somebody can put a tag. white-space:pre-wrap is what keeps
 * the blank lines that were written for alert().
 */
function messageDialogElement(asking) {
    var existing = document.getElementById('siteDialog');
    if (existing) {
        //the same box does both jobs; Cancel only appears when there is something to cancel
        existing.querySelector('.site-dialog-cancel').hidden = !asking;
        return existing;
    }
    if (typeof document.createElement('dialog').showModal !== 'function') {
        return null;      //very old browser: the caller falls back to alert()
    }

    var style = document.createElement('style');
    style.textContent =
        '#siteDialog{border:2px solid #cc0000;border-radius:6px;padding:0;max-width:34em;'
      + 'font-family:Arial,Helvetica,sans-serif;font-size:10pt;color:#333;box-shadow:0 6px 24px rgba(0,0,0,.3)}'
      + '#siteDialog::backdrop{background:rgba(0,0,0,.45)}'
      + '#siteDialog .site-dialog-title{background:#cc0000;color:#fff;font-weight:bold;'
      + 'padding:8px 14px}'
      + '#siteDialog .site-dialog-body{padding:14px;white-space:pre-wrap;line-height:1.45}'
      + '#siteDialog .site-dialog-buttons{padding:0 14px 14px;text-align:right}'
      + '#siteDialog button{min-width:6em;padding:4px 10px;margin-left:8px}'
      + '#siteDialog button[hidden]{display:none}';
    document.head.appendChild(style);

    var dialog = document.createElement('dialog');
    dialog.id = 'siteDialog';
    dialog.innerHTML =
        '<div class="site-dialog-title"></div>'
      + '<div class="site-dialog-body"></div>'
      + '<form method="dialog" class="site-dialog-buttons">'
      + '<button class="site-dialog-cancel" value="cancel">Cancel</button>'
      + '<button value="ok" autofocus>OK</button>'
      + '</form>';
    document.body.appendChild(dialog);
    dialog.querySelector('.site-dialog-cancel').hidden = !asking;
    return dialog;
}

/**
 * Show a message centred in the window. whenClosed, if given, runs once the box is dismissed -
 * which is where focus is put back, since moving it while the dialog is open fights the dialog.
 */
function showMessageDialog(text, whenClosed, title) {
    var dialog = messageDialogElement(false);
    if (!dialog) {
        alert(text);
        if (whenClosed) {
            whenClosed();
        }
        return;
    }
    dialog.querySelector('.site-dialog-title').textContent = title || 'Patrol Calendar';
    dialog.querySelector('.site-dialog-body').textContent = text;
    dialog.onclose = function () {
        if (whenClosed) {
            whenClosed();
        }
    };
    dialog.showModal();
}

/*
 * A yes/no question in the middle of the window, in place of the browser's confirm().
 *
 * confirm() answers synchronously and a <dialog> does not, so this cannot simply be dropped into
 * an onclick that returns a boolean. The shape instead:
 *
 *   onclick="return confirmActionDialog(this, 'message', 'Title')"
 *
 * The first click always returns false and opens the dialog; answering OK marks the element and
 * clicks it again, which re-runs the same onclick, finds the mark and lets the action through.
 * Going back through the element's own click is what keeps a submit button's name and value in
 * the post - form.submit() drops them, and every one of these buttons is dispatched on its name.
 */
function askQuestionDialog(message, title, onYes, onNo) {
    var dialog = messageDialogElement(true);
    if (!dialog) {
        if (confirm(message)) {                  //very old browser
            onYes();
        } else if (onNo) {
            onNo();
        }
        return;
    }

    dialog.querySelector('.site-dialog-title').textContent = title || 'Please confirm';
    dialog.querySelector('.site-dialog-body').textContent = message;
    dialog.onclose = function () {
        if (dialog.returnValue === 'ok') {
            onYes();
        } else if (onNo) {
            onNo();
        }
    };
    dialog.showModal();
}

/** The button form of the question above - see the note on the click replay. */
function confirmActionDialog(element, message, title) {
    if (element.dataset.dialogConfirmed === 'yes') {
        delete element.dataset.dialogConfirmed;   //spent: a later click asks again
        return true;
    }
    askQuestionDialog(message, title, function () {
        element.dataset.dialogConfirmed = 'yes';
        element.click();
    });
    return false;
}
