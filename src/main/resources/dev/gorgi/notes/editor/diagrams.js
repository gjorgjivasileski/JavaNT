(function () {
  'use strict';
  let alive = true;
  let sequence = 0;
  const states = new Set();

  function changed() {
    document.dispatchEvent(new Event('note-diagram-change'));
  }

  function button(label, action) {
    const control = document.createElement('button');
    control.type = 'button';
    control.textContent = label;
    control.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      if (alive) action();
    });
    return control;
  }

  async function draw(state) {
    if (!alive || !state.block.isConnected) return;
    const revision = ++state.revision;
    const source = state.code.textContent;
    state.block.setAttribute('data-diagram-state', 'rendering');
    state.status.textContent = 'Rendering diagram…';
    const scratch = document.createElement('div');
    scratch.setAttribute('data-diagram-ui', 'true');
    scratch.style.cssText = 'position:absolute;left:-100000px;top:0;pointer-events:none';
    state.block.appendChild(scratch);
    try {
      const result = await mermaid.render('note-diagram-' + (++sequence), source, scratch);
      if (!alive || revision !== state.revision || !state.block.isConnected) return;
      // SVG comes only from the bundled renderer in strict mode. Bind no diagram callbacks.
      state.output.innerHTML = result.svg;
      state.code.hidden = true;
      state.status.textContent = '';
      state.block.setAttribute('data-diagram-state', 'ready');
    } catch (error) {
      if (!alive || revision !== state.revision || !state.block.isConnected) return;
      state.output.replaceChildren();
      state.code.hidden = !state.editor.hidden;
      state.status.textContent = 'Could not render diagram: ' + String(error.message || error).slice(0, 400);
      state.block.setAttribute('data-diagram-state', 'error');
    } finally {
      scratch.remove();
    }
  }

  function decorate(block, edit) {
    const code = Array.from(block.children).find(child => child.tagName === 'CODE');
    if (!code) return;
    block.classList.add('mermaid-block');
    block.contentEditable = 'false';
    block.setAttribute('role', 'figure');
    block.setAttribute('aria-label', 'Mermaid diagram');
    const ui = document.createElement('div');
    ui.setAttribute('data-diagram-ui', 'true');
    const bar = document.createElement('div');
    bar.className = 'diagram-toolbar';
    const editor = document.createElement('textarea');
    editor.className = 'diagram-source-editor';
    editor.setAttribute('aria-label', 'Mermaid diagram source');
    editor.spellcheck = false;
    editor.rows = 8;
    editor.value = code.textContent;
    editor.hidden = !edit;
    const output = document.createElement('div');
    output.className = 'diagram-output';
    const status = document.createElement('div');
    status.className = 'diagram-status';
    status.setAttribute('role', 'status');
    const state = { block, code, editor, output, status, revision: 0, timer: null };
    states.add(state);
    const toggle = button(edit ? 'Done editing' : 'Edit diagram', function () {
      editor.hidden = !editor.hidden;
      toggle.textContent = editor.hidden ? 'Edit diagram' : 'Done editing';
      code.hidden = !editor.hidden || block.getAttribute('data-diagram-state') !== 'error';
      if (!editor.hidden) editor.focus();
    });
    bar.append(toggle, button('Delete diagram', function () {
      clearTimeout(state.timer);
      state.revision++;
      block.remove();
      states.delete(state);
      changed();
    }));
    editor.addEventListener('input', function (event) {
      event.stopPropagation();
      if (!alive) return;
      // This first CODE child is the canonical Markdown source, even while rendering fails.
      code.textContent = editor.value;
      state.revision++;
      clearTimeout(state.timer);
      state.timer = setTimeout(() => draw(state), 350);
      changed();
    });
    ui.append(bar, editor, output, status);
    block.appendChild(ui);
    code.hidden = true;
    draw(state);
    if (edit) editor.focus();
  }

  function start(background, foreground, panel, accent, border, font) {
    const style = document.createElement('style');
    style.textContent = `
      .mermaid-block{white-space:normal;position:relative;overflow:auto;padding:10px;
        border:1px solid var(--diagram-border);border-radius:6px}
      .mermaid-block>code{display:block;white-space:pre-wrap;overflow-wrap:anywhere;background:transparent}
      .mermaid-block [hidden]{display:none!important}
      .diagram-toolbar{display:flex;justify-content:flex-end;gap:8px;margin-bottom:8px}
      .diagram-toolbar button{background:transparent;color:inherit;border:1px solid var(--diagram-border);
        border-radius:4px;padding:4px 8px;cursor:pointer;font:inherit;font-size:12px}
      .diagram-toolbar button:hover{border-color:var(--diagram-accent)}
      .diagram-source-editor{box-sizing:border-box;width:100%;padding:8px;background:var(--diagram-bg);
        color:var(--diagram-fg);border:1px solid var(--diagram-border);border-radius:4px;
        resize:vertical;font:14px/1.5 monospace;tab-size:4;white-space:pre}
      .diagram-output svg{display:block;max-width:100%;height:auto;margin:0 auto}
      .diagram-status{font:12px/1.5 sans-serif;overflow-wrap:anywhere}
      .diagram-status:empty{display:none}
    `;
    document.head.appendChild(style);
    const root = document.documentElement.style;
    root.setProperty('--diagram-bg', background);
    root.setProperty('--diagram-fg', foreground);
    root.setProperty('--diagram-border', border);
    root.setProperty('--diagram-accent', accent);
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      suppressErrorRendering: true,
      htmlLabels: false,
      maxTextSize: 50000,
      maxEdges: 500,
      theme: 'base',
      look: 'classic',
      fontFamily: font + ', sans-serif',
      themeVariables: {
        background, primaryColor: panel, primaryTextColor: foreground,
        primaryBorderColor: accent, lineColor: accent, textColor: foreground,
        secondaryColor: panel, tertiaryColor: panel, edgeLabelBackground: background,
        mainBkg: panel, nodeBkg: panel, nodeTextColor: foreground, nodeBorder: accent,
        clusterBkg: panel, clusterBorder: border, titleColor: foreground,
        defaultLinkColor: accent, fontFamily: font + ', sans-serif', fontSize: '14px'
      },
      flowchart: { htmlLabels: false, useMaxWidth: true, theme: 'base', look: 'classic' },
      secure: ['secure', 'securityLevel', 'startOnLoad', 'maxTextSize', 'maxEdges',
        'suppressErrorRendering', 'htmlLabels', 'flowchart', 'themeCSS', 'theme', 'look', 'themeVariables', 'fontFamily']
    });
    document.querySelectorAll('pre.mermaid-block').forEach(block => decorate(block, false));
  }

  function insert(source) {
    const block = document.createElement('pre');
    const code = document.createElement('code');
    code.className = 'language-mermaid';
    code.textContent = source;
    block.appendChild(code);
    const selection = window.getSelection();
    const anchor = selection && selection.anchorNode;
    const element = anchor && (anchor.nodeType === 1 ? anchor : anchor.parentElement);
    const previous = element && element.closest('pre,p,h1,h2,h3,h4,h5,h6,blockquote,ul,ol,table');
    if (previous && document.body.contains(previous)) previous.after(block);
    else document.body.appendChild(block);
    // Leave a normal paragraph after the non-editable figure for continued note writing.
    if (!block.nextSibling) {
      const paragraph = document.createElement('p');
      paragraph.appendChild(document.createElement('br'));
      block.after(paragraph);
    }
    decorate(block, true);
    changed();
  }

  function dispose() {
    alive = false;
    for (const state of states) {
      clearTimeout(state.timer);
      state.revision++;
      state.editor.disabled = true;
      state.block.querySelectorAll('button').forEach(control => { control.disabled = true; });
    }
    states.clear();
  }

  return { start, insert, dispose };
})()
