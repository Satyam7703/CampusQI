(function () {
  const yearOf = item => {
    const semester = Number(item.SEMESTER || item.semester || 0);
    return semester ? Math.ceil(semester / 2) : 0;
  };

  window.renderTimetable = function () {
    const rows = $('#timetableRows');
    if (!rows) return;
    const course = $('#timetableCourseFilter')?.value || '';
    const year = Number($('#timetableYearFilter')?.value || 0);
    const query = ($('#timetableSearch')?.value || '').toLowerCase();
    const list = timetable.filter(item => {
      const itemCourse = String(item.COURSE || item.course || '');
      const itemYear = yearOf(item);
      const text = [
        item.SUBJECT_NAME, item.subject_name, item.BRANCH_NAME,
        item.branch_name, item.TEACHER_NAME, item.teacher_name
      ].join(' ').toLowerCase();
      return (!course || itemCourse === course) &&
        (!year || itemYear === year) &&
        (!query || text.includes(query));
    });

    $('#timetableCount').textContent = `${list.length} entries`;
    rows.innerHTML = list.map(item => {
      const itemYear = yearOf(item);
      const id = item.ID || item.id;
      return `<tr>
        <td><strong>${esc(item.DAY_OF_WEEK || item.day_of_week)}</strong><br><small>${esc(item.START_TIME || item.start_time)}–${esc(item.END_TIME || item.end_time)}</small></td>
        <td><strong>${esc(item.SUBJECT_NAME || item.subject_name)}</strong><br><span class="badge neutral">Year ${itemYear || '—'} · Sem ${esc(item.SEMESTER || item.semester)}</span></td>
        <td>${esc(item.COURSE || item.course)} · ${esc(item.BRANCH_NAME || item.branch_name)}</td>
        <td><span class="badge neutral">${esc(item.SECTION_NAME || item.section_name)}</span></td>
        <td>${esc(item.TEACHER_NAME || item.teacher_name)}</td>
        <td><strong>${esc(item.CLASSROOM || item.classroom)}</strong></td>
        <td><button class="text-btn timetable-edit" data-id="${id}">Edit</button> <button class="text-btn timetable-delete" data-id="${id}">Delete</button></td>
      </tr>`;
    }).join('') || '<tr><td colspan="7" class="empty">No timetable entries match these filters.</td></tr>';

    $$('.timetable-edit').forEach(button => {
      button.onclick = () => editTimetable(button.dataset.id);
    });
    $$('.timetable-delete').forEach(button => {
      button.onclick = async () => {
        if (!confirm('Delete this timetable entry, its sessions, assignments and related marks?')) return;
        try {
          await api(`/timetable/${button.dataset.id}`, { method: 'DELETE' });
          toast('Timetable entry deleted');
          await loadAll();
        } catch (error) {
          toast(error.message, true);
        }
      };
    });
  };

  const updateSemesters = () => {
    const year = Number($('#timetableYearInput')?.value || 1);
    const semester = $('#timetableSemesterInput');
    if (!semester) return;
    const first = (year - 1) * 2 + 1;
    semester.innerHTML = [first, first + 1]
      .map(value => `<option value="${value}">Semester ${value}</option>`)
      .join('');
  };

  ['#timetableCourseFilter', '#timetableYearFilter', '#timetableSearch']
    .forEach(selector => $(selector)?.addEventListener('input', renderTimetable));
  $('#timetableYearInput')?.addEventListener('change', updateSemesters);
  updateSemesters();
  renderTimetable();
})();
