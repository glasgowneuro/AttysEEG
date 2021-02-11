function plot_data(r) {
    var miny = -0.5E-3;
    var maxy =  0.5E-3;

    var winx = $(window).width() * 0.8;
    var winy = $(window).height() / 4;

    var axes_opt = {
              y: {
                valueFormatter: function(y) {
                  y = y * 1000;
                  return y.toPrecision(1);
                },
                axisLabelFormatter: function(y) {
                  y = y * 1000;
                  return y.toPrecision(1);
                },
                axisLabelWidth: 50,
                ticker: function(min, max, pixels, opts, dygraph, vals) {
                  return [{v:1E-4, label:"100"}, {v:-1E-4, label:"-100"}, {v:0, label:"0"}];
                },
              }
    };

    var eeg = new Dygraph(
        document.getElementById("eeg"),
	r, {
	    ylabel: 'EEG / &mu;V',
            axes: axes_opt,
	    animatedZooms: true,
	    xlabel: 't/sec',
	    width: winx,
	    height: winy,
	    valueRange: [ miny, maxy ],
	    visibility: [false, true, false, false, false, false, false, false],
	    zoomCallback: function(minX, maxX, yRanges) {
                delta.updateOptions({dateWindow: [minX,maxX]});
                theta.updateOptions({dateWindow: [minX,maxX]});
                alpha.updateOptions({dateWindow: [minX,maxX]});
                beta.updateOptions({dateWindow: [minX,maxX]});
                gamma.updateOptions({dateWindow: [minX,maxX]});
            },
	}
    );

    var delta = new Dygraph(
        document.getElementById("delta"),
	r, {
	    ylabel: 'delta / &mu;V',
            axes: axes_opt,
	    animatedZooms: true,
	    xlabel: 't/sec',
	    width: winx,
	    height: winy,
	    valueRange: [ miny, maxy ],
	    visibility: [false, false, true, false, false, false, false, false],
	    zoomCallback: function(minX, maxX, yRanges) {
                eeg.updateOptions({dateWindow: [minX,maxX]});
                theta.updateOptions({dateWindow: [minX,maxX]});
                alpha.updateOptions({dateWindow: [minX,maxX]});
                beta.updateOptions({dateWindow: [minX,maxX]});
                gamma.updateOptions({dateWindow: [minX,maxX]});
            },
	}
    );

    var theta = new Dygraph(
        document.getElementById("theta"),
	r, {
	    ylabel: 'theta / &mu;V',
            axes: axes_opt,
	    animatedZooms: true,
	    xlabel: 't/sec',
	    width: winx,
	    height: winy,
	    valueRange: [ miny, maxy ],
	    visibility: [false, false, false, true, false, false, false, false],
	    zoomCallback: function(minX, maxX, yRanges) {
                eeg.updateOptions({dateWindow: [minX,maxX]});
                delta.updateOptions({dateWindow: [minX,maxX]});
                alpha.updateOptions({dateWindow: [minX,maxX]});
                beta.updateOptions({dateWindow: [minX,maxX]});
                gamma.updateOptions({dateWindow: [minX,maxX]});
            },
	}
    );

    var alpha = new Dygraph(
        document.getElementById("alpha"),
	r, {
	    ylabel: 'alpha / &mu;V',
            axes: axes_opt,
	    animatedZooms: true,
	    xlabel: 't/sec',
	    width: winx,
	    height: winy,
	    valueRange: [ miny, maxy ],
	    visibility: [false, false, false, false, true, false, false, false],
	    zoomCallback: function(minX, maxX, yRanges) {
                eeg.updateOptions({dateWindow: [minX,maxX]});
                delta.updateOptions({dateWindow: [minX,maxX]});
                theta.updateOptions({dateWindow: [minX,maxX]});
                beta.updateOptions({dateWindow: [minX,maxX]});
                gamma.updateOptions({dateWindow: [minX,maxX]});
            },
	}
    );

    var beta = new Dygraph(
        document.getElementById("beta"),
	r, {
	    ylabel: 'beta / &mu;V',
            axes: axes_opt,
	    animatedZooms: true,
	    xlabel: 't/sec',
	    width: winx,
	    height: winy,
	    valueRange: [ miny, maxy ],
	    visibility: [false, false, false, false, false, true, false, false],
	    zoomCallback: function(minX, maxX, yRanges) {
                eeg.updateOptions({dateWindow: [minX,maxX]});
                delta.updateOptions({dateWindow: [minX,maxX]});
                theta.updateOptions({dateWindow: [minX,maxX]});
                alpha.updateOptions({dateWindow: [minX,maxX]});
                gamma.updateOptions({dateWindow: [minX,maxX]});
            },
	}
    );

    var gamma = new Dygraph(
        document.getElementById("gamma"),
	r, {
	    ylabel: 'gamma / &mu;V',
            axes: axes_opt,
	    animatedZooms: true,
	    xlabel: 't/sec',
	    width: winx,
	    height: winy,
	    valueRange: [ miny, maxy ],
	    visibility: [false, false, false, false, false, false, true, false],
	    zoomCallback: function(minX, maxX, yRanges) {
                eeg.updateOptions({dateWindow: [minX,maxX]});
                delta.updateOptions({dateWindow: [minX,maxX]});
                theta.updateOptions({dateWindow: [minX,maxX]});
                alpha.updateOptions({dateWindow: [minX,maxX]});
                beta.updateOptions({dateWindow: [minX,maxX]});
            },
	}
    );

    var reset = function() {
        var rng = eeg.xAxisExtremes() 
        eeg.updateOptions({dateWindow: rng});
        delta.updateOptions({dateWindow: rng});
        theta.updateOptions({dateWindow: rng});
        alpha.updateOptions({dateWindow: rng});
        beta.updateOptions({dateWindow: rng});
        gamma.updateOptions({dateWindow: rng});
    };

    var pan = function(dir) {
        var w = eeg.xAxisRange();
        var scale = w[1] - w[0];
        var amount = scale * 0.25 * dir;
        var rng = [ w[0] + amount, w[1] + amount ];
        eeg.updateOptions({dateWindow: rng});
        delta.updateOptions({dateWindow: rng});
        theta.updateOptions({dateWindow: rng});
        alpha.updateOptions({dateWindow: rng});
        beta.updateOptions({dateWindow: rng});
        gamma.updateOptions({dateWindow: rng});
    };

    document.getElementById('full').onclick = function() { reset(); };
    document.getElementById('left').onclick = function() { pan(-1); };
    document.getElementById('right').onclick = function() { pan(+1); };
}

function read_file_contents(fileobj) {
    if (fileobj) {
	var reader = new FileReader();
	reader.readAsText(fileobj, "UTF-8");
	reader.onload = function (evt) {
            document.getElementById("filename").innerHTML = fileobj.name;
	    plot_data(evt.target.result);
	}
	reader.onerror = function (evt) {
	    document.getElementById("message").innerHTML = "error reading file";
	}
    }
}

function upload_file(e) {
    e.preventDefault();
    fileobj = e.dataTransfer.files[0];
    read_file_contents(fileobj)
}

function file_explorer() {
    document.getElementById('selectfile').click();
    document.getElementById('selectfile').onchange = function() {
        fileobj = document.getElementById('selectfile').files[0];
	read_file_contents(fileobj)
    };
}
