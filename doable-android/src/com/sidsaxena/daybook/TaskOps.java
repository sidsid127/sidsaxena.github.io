package com.sidsaxena.daybook;

import java.util.List;

/** Task actions shared by the to-do screen, notifications and widgets. */
final class TaskOps {
    private TaskOps() {}

    /**
     * Ticks a task on or off. Ticking a repeating task also creates its next occurrence (with the same
     * reminder time and unticked subtasks).
     */
    static void setDone(Db db, Db.Task t, boolean done) {
        if (t.done == done) return;
        db.setDone(t.id, done);
        t.done = done;
        if (!done || t.repeat.isEmpty() || t.due == 0) return;
        Db.Task next = new Db.Task();
        next.title = t.title;
        next.category = t.category;
        next.priority = t.priority;
        next.time = t.time;
        next.repeat = t.repeat;
        next.notes = t.notes;
        next.position = t.position;
        next.due = Recurrence.nextOnOrAfter(t.due, t.repeat, Util.startOfDay(System.currentTimeMillis()));
        db.saveTask(next);
        Store st = new Store(db);
        List<Store.Subtask> subs = st.subtasks(t.id);
        for (Store.Subtask s : subs) s.done = false;
        st.replaceSubtasks(next.id, subs);
        // The finished one no longer repeats (the new one carries the rule on).
        t.repeat = "";
        db.saveTask(t);
    }

    static void delete(Db db, long id) {
        new Store(db).deleteSubtasks(id);
        db.deleteTask(id);
    }
}
