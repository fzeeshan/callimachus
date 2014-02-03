// dialog.js
/*
   Copyright (c) 2011 3 Round Stones Inc, Some Rights Reserved
   Licensed under the Apache License, Version 2.0, http://www.apache.org/licenses/LICENSE-2.0
*/

(function($, jQuery){

if (!window.calli) {
    window.calli = {};
}

function findFrameElement(iframe) {
    var elements = $('iframe').filter(function() {
        return iframe == this.contentWindow;
    });
    if (elements.length)
        return elements[0];
    return iframe.frameElement;
}

function asName(title) {
    if (!title)
        return "iframe";
    return title.toLowerCase().replace(/^\s+/,'').replace(/\s+$/,'').replace(/\s/g,'-').replace(/[^\-\w]/g,'_');
}

function asUniqueName(title) {
    var name = asName(title);
    if (!frames[name] || frames[name].name != name)
        return name;
    var i = 1;
    while (frames[name + i] && frames[name + i].name == name + i) {
        i++;
    }
    return name + i;
}

window.calli.closeDialog = function(iframe) {
    var frameElement = findFrameElement(iframe);
    $(frameElement).closest('.modal').modal('hide');
};

// settings : {buttons:{label:handler}, onmessage:handler, onclose:handler}
window.calli.openDialog = function(url, title, settings) {
    var markup = ['<div class="modal fade" tabindex="-1" role="dialog" aria-hidden="true">',
        '  <div class="modal-dialog modal-lg">',
        '    <div class="modal-content">',
        '      <div class="modal-header">',
        '        <button type="button" class="close" data-dismiss="modal" aria-hidden="true">×</button>',
        '        <h4 class="modal-title"></h4>',
        '      </div>',
        '      <iframe seamless="seamless" width="100%"></iframe>',
        '      <div class="modal-footer" style="margin-top:0">',
        '      </div>',
        '    </div>',
        '  </div>',
        '</div>'].join('\n');
    var modal = $(markup);
    modal.find('.modal-title').text(title);
    for (var label in settings.buttons) {
        var button = $('<button></button>');
        button.attr("type", "button");
        button.attr("class", "btn btn-default");
        button.text(label);
        modal.find('.modal-footer').append(button);
        button.on('click', settings.buttons[label]);
    }
    var iframe = modal.find('iframe');
    iframe.css('height', 0.6 * $(window).height());
    $('body').append(modal);
    // inter-window message processor
    var handle = function(event) {
        if (event.originalEvent.source == iframe[0].contentWindow) {
            var data = event.originalEvent.data;
            if (typeof settings.onmessage == 'function') {
                if (!event.source) {
                    event.source = event.originalEvent.source;
                }
                if (!event.data) {
                    event.data = event.originalEvent.data;
                }
                settings.onmessage(event);
            }
        }
    };
    $(window).bind('message', handle);
    // close
    modal.on("hidden.bs.modal", function(event, ui) {
        $(document).trigger("calliCloseDialog");
        $(window).unbind('message', handle);
        modal.remove();
        if (typeof settings.onclose == 'function') {
            settings.onclose();
        }
    });
    // trigger open
    var e = jQuery.Event("calliOpenDialog");
    $(document).trigger(e);
    if (e.isDefaultPrevented()) {
        return null;
    } else {
        modal.modal('show');
        iframe.load(calli.wait().over);
        iframe[0].src = url;
        if (typeof settings.onlookup == 'function') {
            var dialogTitle = modal.find(".modal-title");
            var form = $("<form></form>");
            var searchTerms = $("<input/>");
            searchTerms.attr("placeholder", "Lookup..");
            searchTerms.addClass("form-control");
            form.append(searchTerms);
            form.attr("role", "form");
            form.addClass('pull-right form-inline');
            form.css('padding-right', '15px');
            dialogTitle.append(form);
            form.submit(function(event) {
                event.preventDefault();
                if (searchTerms.val()) {
                    settings.onlookup(searchTerms.val());
                }
                return false;
            });
        }
        var win = iframe[0].contentWindow;
        try {
            win.close = function() { // not sure when this is called
                window.setTimeout(function() {
                    calli.closeDialog(win);
                }, 0);
            };
        } catch (e) {
            // use calli.closeDialog directly
        }
        return win;
    }
};

})(jQuery, jQuery);

