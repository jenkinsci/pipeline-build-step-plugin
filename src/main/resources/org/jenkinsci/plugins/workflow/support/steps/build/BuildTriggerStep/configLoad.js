Behaviour.specify(".textbox-params-reference-holder", 'textbox-onblur-function', 0, function (e) {
    var id = e.getAttribute('data-id');
    var textbox = document.getElementById(id);

    textbox.onblur = function(el) {
        var div = document.getElementById('params');
    fetch(document.querySelector('#params').dataset.descriptor + '/parameters?job=' + encodeURIComponent(document.getElementById(document.querySelector('#params').dataset.jobfield).value) + '&amp;context=${descriptor.contextEncoded}').then((rsp) => {
        rsp.text().then((responseText) => {
            if (rsp.ok) {
                div.innerHTML = responseText;
                Behaviour.applySubtree(div);
            } else {
                div.innerHTML = "<b>ERROR</b>: Failed to load parameter definitions: " + rsp.statusText;
            }
        });
    });
    }
});