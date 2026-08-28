use crate::{
    core::{Error, dealer::protocol::SetQueueCommand},
    state::{ConnectState, context::ContextType},
};

// LOCAL PATCH: `handle_shuffle` is gone from here.
//
// It was the half of shuffling that reorders the context, and this device does
// not do that half any more: the app owns the running order and hands it over
// with `Spirc::set_queue_tracks`, so a device that shuffled as well made two
// orders for one queue. What is left of shuffle is the flag, which
// `SpircTask::handle_shuffle` writes straight into the published state.
//
// Deleted rather than left unused so the next person reading this file is not
// told there are two ways to shuffle. `shuffle_new` and `shuffle_restore` in
// `options.rs` are untouched: a transfer still arrives with the account's own
// permutation, and that one is not this device's to redraw.
impl ConnectState {
    pub fn handle_set_queue(&mut self, set_queue: SetQueueCommand) {
        self.set_next_tracks(set_queue.next_tracks);
        self.set_prev_tracks(set_queue.prev_tracks);
        self.update_queue_revision();
    }

    pub fn handle_set_repeat_context(&mut self, repeat: bool) -> Result<(), Error> {
        self.set_repeat_context(repeat);

        if repeat {
            if let ContextType::Autoplay = self.fill_up_context {
                self.fill_up_context = ContextType::Default;
            }
        }

        let ctx = self.get_context(ContextType::Default)?;
        let current_track =
            ConnectState::find_index_in_context(ctx, |t| self.current_track(|t| &t.uri) == &t.uri)?;
        self.reset_playback_to_position(Some(current_track))
    }
}
