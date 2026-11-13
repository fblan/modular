// Highlights the arrows of the module selected in the diagram. The page is complete without it.
(function () {
	var diagram = document.querySelector('svg.diagram');
	var note = document.getElementById('diagram-selection');
	if (!diagram || !note) {
		return;
	}
	var nodes = Array.prototype.slice.call(diagram.querySelectorAll('.node'));
	var edges = Array.prototype.slice.call(diagram.querySelectorAll('.edge'));
	var selected = null;

	function modules(count) {
		return count === 1 ? '1 module' : count + ' modules';
	}

	function select(node) {
		selected = node === selected ? null : node;
		var id = selected ? selected.getAttribute('data-module') : null;
		var uses = 0;
		var usedBy = 0;
		var linked = {};
		edges.forEach(function (edge) {
			var from = edge.getAttribute('data-from');
			var to = edge.getAttribute('data-to');
			var active = selected !== null && (from === id || to === id);
			edge.classList.toggle('active', active);
			if (active && from === id) {
				uses++;
				linked[to] = true;
			}
			if (active && to === id) {
				usedBy++;
				linked[from] = true;
			}
		});
		nodes.forEach(function (other) {
			other.classList.toggle('selected', other === selected);
			other.classList.toggle('linked', linked[other.getAttribute('data-module')] === true);
			other.setAttribute('aria-pressed', other === selected ? 'true' : 'false');
		});
		diagram.classList.toggle('has-selection', selected !== null);
		note.textContent = '';
		note.hidden = selected === null;
		if (selected !== null) {
			var name = document.createElement('strong');
			name.textContent = selected.getAttribute('data-name');
			var link = document.createElement('a');
			link.setAttribute('href', selected.getAttribute('data-section'));
			link.textContent = 'See its details';
			note.appendChild(name);
			note.appendChild(document.createTextNode(' uses ' + modules(uses) + ' and is used by ' + modules(usedBy) + '. '));
			note.appendChild(link);
		}
	}

	nodes.forEach(function (node) {
		node.setAttribute('tabindex', '0');
		node.setAttribute('role', 'button');
		node.setAttribute('aria-pressed', 'false');
		node.addEventListener('click', function () {
			select(node);
		});
		node.addEventListener('keydown', function (event) {
			if (event.key === 'Enter' || event.key === ' ') {
				event.preventDefault();
				select(node);
			}
		});
	});
	// With buttons inside, the diagram is no longer one image
	diagram.setAttribute('role', 'group');
	diagram.classList.add('interactive');
	Array.prototype.forEach.call(document.querySelectorAll('.js-only'), function (element) {
		element.hidden = false;
	});
})();

// Filters the modules table and the module sections by name. The page is complete without it.
(function () {
	var input = document.getElementById('module-filter');
	var counter = document.getElementById('module-filter-count');
	if (!input || !counter) {
		return;
	}
	var section = document.getElementById('modules');
	var rows = Array.prototype.slice.call(section.querySelectorAll('tr[data-name]'));
	var articles = Array.prototype.slice.call(section.querySelectorAll('article[data-name]'));

	function filter() {
		var text = input.value.trim().toLowerCase();
		var shown = 0;
		rows.forEach(function (row) {
			var match = row.getAttribute('data-name').toLowerCase().indexOf(text) !== -1;
			row.hidden = !match;
			if (match) {
				shown++;
			}
		});
		articles.forEach(function (article) {
			article.hidden = article.getAttribute('data-name').toLowerCase().indexOf(text) === -1;
		});
		counter.textContent = text === '' ? '' : shown === 0 ? 'No module matches'
			: shown + ' of ' + rows.length + ' modules';
	}

	input.addEventListener('input', filter);
	input.closest('.filter').hidden = false;
})();
