Behaviour.specify(".textbox-params-reference-holder", 'textbox-onblur-function', 0, function (e) {
    var id = e.getAttribute('data-id');
    var textbox = document.getElementById(id);
    var context = e.getAttribute('data-context');

    textbox.onblur = function(el) {
        var div = document.getElementById('params');
        const query = new URLSearchParams({
            job: document.getElementById(document.querySelector('#params').dataset.jobfield).value,
            context: context
        });
        fetch(`${document.querySelector('#params').dataset.descriptor}/parameters?${query}`).then((rsp) => {
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