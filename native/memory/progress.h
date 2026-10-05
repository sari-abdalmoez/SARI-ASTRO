#pragma once
namespace sari {
class ProgressTracker {
 public:
  explicit ProgressTracker(double total) : total_(total) {}
  void start(double now) { t0_ = last_t_ = now; done_ = last_done_ = 0; rate_ = 0; now_ = now; }
  void update(double done, double now) {
    done_ = done; double dt = now - last_t_;
    if (dt > 1e-6 && done > last_done_) {
      double inst = (done - last_done_) / dt;
      rate_ = rate_ > 0 ? 0.3 * inst + 0.7 * rate_ : inst; last_t_ = now; last_done_ = done;
    }
    now_ = now;
  }
  double fraction() const { return total_ > 0 ? done_ / total_ : 0; }
  double elapsed() const { return now_ - t0_; }
  double etaSeconds() const { return rate_ > 0 ? (total_ - done_) / rate_ : -1; }
 private:
  double total_, done_ = 0, last_done_ = 0, t0_ = 0, last_t_ = 0, now_ = 0, rate_ = 0;
};
}
