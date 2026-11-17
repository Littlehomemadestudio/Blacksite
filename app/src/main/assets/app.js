(() => {
  const views = new Set(['home', 'assistant', 'library', 'workflows', 'audio', 'settings']);
  const labels = { home: 'Overview', assistant: 'Assistant', library: 'Knowledge library', workflows: 'Workflow studio', audio: 'Audio memory', settings: 'Settings' };
  function openView(view) {
    if (!views.has(view)) return;
    if (window.OracleNative && typeof window.OracleNative.open === 'function') window.OracleNative.open(view);
  }
  function navigate(view) {
    if (!views.has(view)) return;
    document.querySelectorAll('[data-view]').forEach(el => {
      const active = el.dataset.view === view;
      el.classList.toggle('active', active);
      if (el.classList.contains('side-link')) el.classList.toggle('selected', active);
    });
    const crumb = document.querySelector('.page-toolbar .breadcrumb strong');
    if (crumb) crumb.textContent = labels[view];
    openView(view);
  }
  window.openView = openView;
  window.navigate = navigate;
  const today = document.getElementById('today');
  if (today) today.textContent = new Intl.DateTimeFormat(undefined, { month: 'short', day: '2-digit' }).format(new Date()).toUpperCase();
  document.addEventListener('keydown', event => {
    if ((event.metaKey || event.ctrlKey) && ['1','2','3','4','5','6'].includes(event.key)) {
      event.preventDefault();
      navigate(['home','assistant','library','workflows','audio','settings'][Number(event.key)-1]);
    }
  });
})();