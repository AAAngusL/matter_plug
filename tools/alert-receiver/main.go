// Matter Plug alert receiver.
//
// Usage: alert-receiver [-port 8080] [-bind 0.0.0.0] [-log alerts.log]
//
//	[-notify-cmd "notify-send 'Matter Plug' {message}"] [-silence-min 0]
//
// Accepts a POST with a JSON object on any path, prints and logs it, and answers 200.
// Runs -notify-cmd for "started" and "reminder" alerts, and for its own silence alarm.
// {message} in -notify-cmd is replaced by the text (quoted for the shell on Linux and macOS);
// the text is also in the environment variable ALERT_MESSAGE, which is the safe way on Windows.
//
// Silence alarm: after a "status" request tells it when the next check is due, it expects some request
// within 3 intervals (at least 3 x 10 minutes, plus 1 minute). If none arrives it logs SILENT, runs the
// notify command, and repeats every 60 minutes until a request arrives (then it logs BACK).
// -silence-min N overrides the limit with a fixed N minutes.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"time"
)

type alert struct {
	Alert          string `json:"alert"`
	State          string `json:"state"`
	Message        string `json:"message"`
	Battery        *int   `json:"battery"`
	Charging       *bool  `json:"charging"`
	Time           string `json:"time"`
	NextCheckInMin *int   `json:"next_check_in_min"`
}

var (
	notifyCmd  = flag.String("notify-cmd", "", "command to run for alerts; {message} is replaced")
	logPath    = flag.String("log", "", "append log lines to this file")
	silenceMin = flag.Int("silence-min", 0, "fixed silence limit in minutes (0 = from the status requests)")

	mu          sync.Mutex
	lastSeen    time.Time
	nextMin     int // from the latest status request; 0 until one arrives
	silent      bool
	lastSilence time.Time
)

func logLine(format string, a ...any) {
	line := time.Now().Format("2006-01-02 15:04:05") + "  " + fmt.Sprintf(format, a...)
	fmt.Println(line)
	if *logPath != "" {
		if f, err := os.OpenFile(*logPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644); err == nil {
			fmt.Fprintln(f, line)
			f.Close()
		}
	}
}

func shellQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'" }

func notify(message string) {
	if *notifyCmd == "" {
		return
	}
	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		text := strings.NewReplacer(`"`, `'`, "%", " percent").Replace(message)
		cmd = exec.Command("cmd", "/C", strings.ReplaceAll(*notifyCmd, "{message}", `"`+text+`"`))
	} else {
		cmd = exec.Command("sh", "-c", strings.ReplaceAll(*notifyCmd, "{message}", shellQuote(message)))
	}
	cmd.Env = append(os.Environ(), "ALERT_MESSAGE="+message)
	if err := cmd.Start(); err != nil {
		logLine("notify command failed: %v", err)
		return
	}
	go cmd.Wait()
}

func limit() time.Duration {
	if *silenceMin > 0 {
		return time.Duration(*silenceMin) * time.Minute
	}
	if nextMin == 0 {
		return 0 // no status seen yet: no silence alarm
	}
	iv := nextMin
	if iv < 10 {
		iv = 10 // an idle phone spaces alarms at least about 9 minutes apart
	}
	return time.Duration(3*iv+1) * time.Minute
}

func handle(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"ok":false,"error":"POST only"}`, http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 64<<10))
	var a alert
	if err == nil {
		err = json.Unmarshal(body, &a)
	}
	if err != nil {
		logLine("BAD REQUEST from %s: %v", r.RemoteAddr, err)
		http.Error(w, `{"ok":false}`, http.StatusBadRequest)
		return
	}
	mu.Lock()
	lastSeen = time.Now()
	if a.Alert == "status" && a.NextCheckInMin != nil && *a.NextCheckInMin > 0 {
		nextMin = *a.NextCheckInMin
	}
	back := silent
	silent = false
	mu.Unlock()

	bat, chg := "?", "?"
	if a.Battery != nil {
		bat = fmt.Sprint(*a.Battery)
	}
	if a.Charging != nil {
		chg = fmt.Sprint(*a.Charging)
	}
	logLine("%s/%s  battery=%s plugged_in=%s event_time=%s from=%s  %q", a.Alert, a.State, bat, chg, a.Time, r.RemoteAddr, a.Message)
	if back {
		logLine("BACK: the phone is reporting again")
		notify("Matter Plug: the phone is reporting again.")
	}
	if a.State == "started" || a.State == "reminder" {
		notify(a.Message)
	}
	io.WriteString(w, `{"ok":true}`)
}

func watchSilence() {
	for range time.Tick(30 * time.Second) {
		mu.Lock()
		l := limit()
		quiet := time.Since(lastSeen)
		fire := l > 0 && !lastSeen.IsZero() && quiet >= l && (!silent || time.Since(lastSilence) >= time.Hour)
		if fire {
			silent = true
			lastSilence = time.Now()
		}
		seen := lastSeen
		mu.Unlock()
		if fire {
			msg := fmt.Sprintf("Matter Plug: no report from the phone for %d minutes (last seen %s).", int(quiet.Minutes()), seen.Format("15:04"))
			logLine("SILENT: %s", msg)
			notify(msg)
		}
	}
}

func main() {
	port := flag.Int("port", 8080, "port to listen on")
	bind := flag.String("bind", "0.0.0.0", "address to listen on")
	flag.Parse()
	addr := fmt.Sprintf("%s:%d", *bind, *port)
	logLine("listening on %s", addr)
	go watchSilence()
	http.HandleFunc("/", handle)
	log.Fatal(http.ListenAndServe(addr, nil))
}
