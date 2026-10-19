/* Installed by WebEngine.executeScript from this packaged resource, never from note content. */
(function () {
  'use strict';

  var body = document.body;
  var selected = null;
  var gesture = null;
  var disposed = false;
  var refreshFrame = 0;
  var originalMinHeight = body.style.minHeight;
  var outline = document.createElement('div');
  outline.className = 'note-image-selection';
  outline.setAttribute('data-image-ui', 'true');
  outline.setAttribute('contenteditable', 'false');
  outline.style.display = 'none';
  outline.setAttribute('aria-label', 'Selected image');
  var resize = makeHandle('resize', 'Drag to resize image');
  var move = makeHandle('move', 'Drag to move image');
  outline.appendChild(resize);
  outline.appendChild(move);
  body.appendChild(outline);

  function makeHandle(kind, description) {
    var handle = document.createElement('span');
    handle.setAttribute('data-image-ui', 'true');
    handle.setAttribute('data-image-handle', kind);
    handle.setAttribute('contenteditable', 'false');
    handle.setAttribute('title', description);
    handle.setAttribute('aria-label', description);
    return handle;
  }

  function images() {
    return Array.prototype.slice.call(document.querySelectorAll('img.note-image[data-image-id]'));
  }

  function newId() {
    var bytes = new Uint8Array(16);
    if (window.crypto && window.crypto.getRandomValues) {
      window.crypto.getRandomValues(bytes);
    } else {
      for (var i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
    }
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    var hex = Array.prototype.map.call(bytes, function (value) {
      return ('0' + value.toString(16)).slice(-2);
    }).join('');
    return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16)
        + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
  }

  function normalizeIds() {
    var all = images();
    var reserved = new Set(all.map(function (image) {
      return image.getAttribute('data-image-id');
    }));
    var seen = new Set();
    var changed = false;
    all.forEach(function (image) {
      var id = image.getAttribute('data-image-id');
      if (seen.has(id)) {
        do { id = newId(); } while (reserved.has(id));
        image.setAttribute('data-image-id', id);
        reserved.add(id);
        changed = true;
      }
      seen.add(id);
      image.setAttribute('draggable', 'false');
      image.setAttribute('contenteditable', 'false');
    });
    if (changed) dispatch('note-image-change');
  }

  function imageFrom(node) {
    return node && node.nodeType === 1 && node.matches('img.note-image[data-image-id]')
        ? node : null;
  }

  function value(image, name) {
    return parseInt(image.getAttribute('data-image-' + name), 10) || 0;
  }

  function isFloating(image) {
    return image && image.getAttribute('data-image-mode') !== 'inline';
  }

  function clamp(number, minimum, maximum) {
    return Math.round(Math.max(minimum, Math.min(maximum, number)));
  }

  function setValue(image, name, number) {
    image.setAttribute('data-image-' + name, String(number));
    var styleName = {x: 'left', y: 'top', width: 'width', height: 'height', z: 'zIndex'}[name];
    image.style[styleName] = name === 'z' ? String(number)
        : name === 'height' && !isFloating(image) ? 'auto' : number + 'px';
  }

  function dispatch(name) {
    if (disposed) return;
    var event = document.createEvent('Event');
    event.initEvent(name, false, false);
    document.dispatchEvent(event);
  }

  function extendPage() {
    var bottom = 0;
    images().forEach(function (image) {
      if (isFloating(image)) {
        bottom = Math.max(bottom, value(image, 'y') + value(image, 'height') + 16);
      }
    });
    body.style.minHeight = bottom ? 'max(80vh, ' + bottom + 'px)' : originalMinHeight;
  }

  function showOutline() {
    if (!selected || !body.contains(selected)) {
      var removed = !!selected;
      selected = null;
      body.removeAttribute('data-selected-image-id');
      outline.style.display = 'none';
      if (removed) dispatch('note-image-selection');
      return;
    }
    var box = selected.getBoundingClientRect();
    var origin = body.getBoundingClientRect();
    body.setAttribute('data-selected-image-id', selected.getAttribute('data-image-id'));
    outline.style.display = 'block';
    outline.style.left = (box.left - origin.left) + 'px';
    outline.style.top = (box.top - origin.top) + 'px';
    outline.style.width = box.width + 'px';
    outline.style.height = box.height + 'px';
    move.style.display = isFloating(selected) ? 'block' : 'none';
  }

  function refresh() {
    if (disposed || refreshFrame) return;
    // Font scaling can move an inline image without firing a DOM resize or input event.
    refreshFrame = window.requestAnimationFrame(function () {
      refreshFrame = 0;
      if (disposed) return;
      showOutline();
      extendPage();
    });
  }

  function selectImage(image) {
    if (selected === image) {
      showOutline();
      return !!image;
    }
    selected = image;
    showOutline();
    dispatch('note-image-selection');
    return !!selected;
  }

  function select(id) {
    return selectImage(images().filter(function (image) {
      return image.getAttribute('data-image-id') === id;
    })[0] || null);
  }

  function selectAt(x, y) {
    // Geometry also finds an image behind a paragraph, which intentionally receives text clicks.
    var candidates = images().filter(function (image) {
      var box = image.getBoundingClientRect();
      return x >= box.left && x <= box.right && y >= box.top && y <= box.bottom;
    });
    candidates.sort(function (first, second) {
      return value(first, 'z') - value(second, 'z');
    });
    return selectImage(candidates.length ? candidates[candidates.length - 1] : null);
  }

  function begin(event) {
    if (event.button !== 0 && event.button !== 2) return;
    var handle = event.target.getAttribute && event.target.getAttribute('data-image-handle');
    var image = imageFrom(event.target);
    if (!image && handle) image = selected;
    if (!image) {
      if (event.button === 0) selectImage(null);
      return;
    }
    selectImage(image);
    if (event.button !== 0) return;
    event.preventDefault();
    event.stopPropagation();
    if (!isFloating(image) && handle !== 'resize') return;
    var box = image.getBoundingClientRect();
    if (box.width <= 0 || box.height <= 0) return;
    gesture = {
      image: image,
      kind: handle === 'resize' ? 'resize' : 'move',
      startX: event.clientX,
      startY: event.clientY,
      scrollX: window.pageXOffset,
      scrollY: window.pageYOffset,
      x: value(image, 'x'),
      y: value(image, 'y'),
      width: box.width,
      height: box.height,
      originalWidth: value(image, 'width'),
      originalHeight: value(image, 'height'),
      changed: false
    };
  }

  function drag(event) {
    if (!gesture) return;
    event.preventDefault();
    var dx = event.clientX - gesture.startX + window.pageXOffset - gesture.scrollX;
    var dy = event.clientY - gesture.startY + window.pageYOffset - gesture.scrollY;
    var image = gesture.image;
    if (gesture.kind === 'resize') {
      // Project the pointer onto the image diagonal to keep its original aspect ratio.
      var factor = 1 + (dx * gesture.width + dy * gesture.height)
          / (gesture.width * gesture.width + gesture.height * gesture.height);
      var smallest = Math.max(1 / gesture.width, 1 / gesture.height);
      var largest = Math.min(8192 / gesture.width, 8192 / gesture.height);
      factor = Math.max(smallest, Math.min(largest, factor));
      var width = clamp(gesture.width * factor, 1, 8192);
      var height = clamp(gesture.height * factor, 1, 8192);
      setValue(image, 'width', width);
      setValue(image, 'height', height);
      gesture.changed = width !== gesture.originalWidth || height !== gesture.originalHeight;
    } else {
      var x = clamp(gesture.x + dx, 0, 100000);
      var y = clamp(gesture.y + dy, 0, 100000);
      setValue(image, 'x', x);
      setValue(image, 'y', y);
      gesture.changed = x !== gesture.x || y !== gesture.y;
    }
    showOutline();
    extendPage();
  }

  function finish() {
    if (!gesture) return;
    var changed = gesture.changed;
    gesture = null;
    if (changed) dispatch('note-image-change');
  }

  function cancelGesture() {
    if (!gesture) return;
    var image = gesture.image;
    if (gesture.kind === 'move') {
      setValue(image, 'x', gesture.x);
      setValue(image, 'y', gesture.y);
    }
    setValue(image, 'width', gesture.originalWidth);
    setValue(image, 'height', gesture.originalHeight);
    gesture = null;
    showOutline();
    extendPage();
  }

  function reorder(front) {
    finish();
    if (!selected || !body.contains(selected)) return;
    setMode(true);
    // Renumber at most once per action, so repeated commands never exceed saved z-index bounds.
    var ordered = images().filter(function (image) { return image !== selected; });
    ordered.sort(function (first, second) { return value(first, 'z') - value(second, 'z'); });
    var behind = ordered.filter(function (image) { return value(image, 'z') < 0; });
    var ahead = ordered.filter(function (image) { return value(image, 'z') >= 0; });
    behind.forEach(function (image, index) { setValue(image, 'z', index - behind.length); });
    ahead.forEach(function (image, index) { setValue(image, 'z', index + 1); });
    setValue(selected, 'z', front ? ahead.length + 1 : -behind.length - 1);
    showOutline();
    dispatch('note-image-change');
  }

  function setMode(floating) {
    if (!selected || isFloating(selected) === floating) return false;
    if (floating) {
      var box = selected.getBoundingClientRect();
      var origin = body.getBoundingClientRect();
      setValue(selected, 'x', clamp(box.left - origin.left, 0, 100000));
      setValue(selected, 'y', clamp(box.top - origin.top, 0, 100000));
      selected.style.position = 'absolute';
      selected.style.maxWidth = 'none';
      selected.style.width = value(selected, 'width') + 'px';
      selected.style.height = value(selected, 'height') + 'px';
      setValue(selected, 'z', 1);
    } else {
      selected.style.position = 'relative';
      selected.style.maxWidth = '100%';
      selected.style.height = 'auto';
      selected.style.left = 'auto';
      selected.style.top = 'auto';
      setValue(selected, 'z', 0);
    }
    selected.setAttribute('data-image-mode', floating ? 'floating' : 'inline');
    showOutline();
    extendPage();
    return true;
  }

  function setFloating(floating) {
    finish();
    if (setMode(!!floating)) dispatch('note-image-change');
  }

  function deleteSelected() {
    finish();
    if (!selected || !body.contains(selected)) return;
    selected.parentNode.removeChild(selected);
    selectImage(null);
    extendPage();
    dispatch('note-image-change');
  }

  function keydown(event) {
    if (!selected) return;
    if (event.key === 'Escape') {
      cancelGesture();
      selectImage(null);
      event.preventDefault();
    } else if (event.key === 'Delete' || event.key === 'Backspace') {
      deleteSelected();
      event.preventDefault();
      event.stopPropagation();
    } else if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End',
        'PageUp', 'PageDown', 'Tab', 'Enter'].indexOf(event.key) >= 0
        || (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey)
        || ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'a')) {
      finish();
      selectImage(null);
    }
  }

  function input() {
    // The browser resumes editing text at its caret; subsequent Backspace must edit that text.
    // Commit any in-flight image gesture before this independent text edit is serialized.
    finish();
    selectImage(null);
    normalizeIds();
    extendPage();
  }

  function dragStart(event) {
    if (imageFrom(event.target)) event.preventDefault();
  }

  // Keep the DOM position when changing mode so returning inline restores the text insertion point.
  // Ordinary paragraphs are static; floating images use the positioned body as containing block.
  images().forEach(function (image) {
    image.setAttribute('draggable', 'false');
    image.setAttribute('contenteditable', 'false');
  });
  extendPage();
  document.addEventListener('mousedown', begin, true);
  document.addEventListener('mousemove', drag, true);
  document.addEventListener('mouseup', finish, true);
  document.addEventListener('keydown', keydown, true);
  document.addEventListener('dragstart', dragStart, true);
  document.addEventListener('input', input, false);
  document.addEventListener('load', showOutline, true);
  window.addEventListener('resize', showOutline, false);
  window.addEventListener('blur', finish, false);

  return {
    select: select,
    normalizeIds: normalizeIds,
    refresh: refresh,
    selectAt: selectAt,
    hasSelection: function () { return !!selected && body.contains(selected); },
    isFloating: function () { return isFloating(selected); },
    setFloating: setFloating,
    bringToFront: function () { reorder(true); },
    sendToBack: function () { reorder(false); },
    deleteSelected: deleteSelected,
    dispose: function () {
      cancelGesture();
      disposed = true;
      if (refreshFrame) window.cancelAnimationFrame(refreshFrame);
      document.removeEventListener('mousedown', begin, true);
      document.removeEventListener('mousemove', drag, true);
      document.removeEventListener('mouseup', finish, true);
      document.removeEventListener('keydown', keydown, true);
      document.removeEventListener('dragstart', dragStart, true);
      document.removeEventListener('input', input, false);
      document.removeEventListener('load', showOutline, true);
      window.removeEventListener('resize', showOutline, false);
      window.removeEventListener('blur', finish, false);
      if (outline.parentNode) outline.parentNode.removeChild(outline);
      body.removeAttribute('data-selected-image-id');
      body.style.minHeight = originalMinHeight;
    }
  };
}())
