function loadParams() {
    var div = document.getElementById('params');
    fetch('${descriptor.descriptorUrl}/parameters?job=' + encodeURIComponent(document.getElementById('${jobFieldId}').value) + '&amp;context=${descriptor.contextEncoded}').then((rsp) => {
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