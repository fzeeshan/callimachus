// submit-turtle.js
/*
   Copyright (c) 2014 3 Round Stones Inc., Some Rights Reserved
   Licensed under the Apache License, Version 2.0, http://www.apache.org/licenses/LICENSE-2.0
*/

(function($){

var calli = window.calli || (window.calli={});

calli.submitTurtle = function(event, local) {
    event.preventDefault();
    var form = calli.fixEvent(event).target;
    var slug = encodeURI(local || '').replace(/%25(\w\w)/g, '%$1').replace(/%20/g, '+');
    calli.resolve(form).then(function(form){
        var previously = form.getAttribute("resource");
        var ns = calli.getFormAction(event.target).replace(/\?.*/,'').replace(/\/?$/, '/');
        var resource = ns + slug;
        form.setAttribute("resource", resource);
        try {
            return calli.copyResourceData(form);
        } finally {
            if (previously) {
                form.setAttribute("resource", previously);
            }
        }
    }).then(function(data){
        data.results.bindings.push({
            s: {type:'uri', value: data.head.link[0]},
            p: {type:'uri', value: 'http://purl.org/dc/terms/created'},
            o: {
                type:'literal',
                value: new Date().toISOString(),
                datatype: "http://www.w3.org/2001/XMLSchema#dateTime"
            }
        });
        return data;
    }).then(function(data){
        return calli.postTurtle(calli.getFormAction(form), data, {slug: slug});
    }).then(function(redirect){
        if (redirect) {
            if (window.parent != window && parent.postMessage) {
                parent.postMessage('POST resource\n\n' + redirect, '*');
            }
            window.location.replace(redirect);
        } else {
            calli.loading(form)();
        }
    }, calli.loading(form, calli.error));
};

calli.postTurtle = function(url, data, headers) {
    var serializer = new TurtleSerializer();
    serializer.setNullUri(data.base);
    serializer.setMappings(data.prefix);
    data.results.bindings.forEach(function(triple){
        serializer.addTriple(triple);
    });
    return calli.postText(url, serializer.toString(), "text/turtle", headers);
};

})(jQuery);

