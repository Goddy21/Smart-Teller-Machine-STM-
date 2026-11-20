#!/bin/bash
OUT=/home/gokaka/moved/slow-check.txt; exec > "$OUT" 2>&1
s(){ echo; echo "===== $* ====="; }
t(){ curl -sk -o /dev/null --max-time 20 -w "%{http_code}  first byte %{time_starttransfer}s  total %{time_total}s" "$@"; echo "  $*" | cut -c1-120; }

s "1. Each site from the server itself (slow here = the app; fast here but slow for users = network)"
for h in admin.blueriverafrica.net helpdesk.blueriverafrica.net brits.blueriverafrica.net www.bluerivertech.net test.blueriverafrica.net; do
  t --resolve $h:443:127.0.0.1 https://$h/
done
t https://10.8.0.1:8443/
t http://127.0.0.1:4001/

s "2. DNS on the server (should be well under 1 s)"
cat /etc/resolv.conf | grep -v '^#'
for n in google.com smtppro.zoho.com acme-v02.api.letsencrypt.org; do /usr/bin/time -f "$n: %es" getent hosts $n >/dev/null; done
time hostname -f

s "3. Apache workers in use vs allowed"
httpd -V 2>/dev/null | grep -i mpm
grep -rhiE "^\s*(MaxRequestWorkers|ServerLimit|ThreadsPerChild|MaxClients|KeepAliveTimeout|Timeout)\b" /etc/httpd/ 2>/dev/null | sort | uniq -c
echo "httpd processes: $(pgrep -c httpd)"
ss -tn state established '( sport = :443 or sport = :80 )' | wc -l | xargs echo "open web connections:"
journalctl -u httpd --since "24 hours ago" --no-pager | grep -iE "MaxRequestWorkers|server reached" | tail -3
grep -h "MaxRequestWorkers\|server reached" /var/log/httpd/error_log 2>/dev/null | tail -3

s "4. Who is hitting Apache hardest (last 10,000 requests per site)"
for f in /var/log/httpd/*access*log; do [ -s "$f" ] || continue; echo "-- $f: $(tail -10000 "$f" | awk '{print $4}' | cut -d: -f1-3 | uniq -c | tail -3 | tr '\n' ' ')"; tail -10000 "$f" | awk '{print $1}' | sort | uniq -c | sort -rn | head -5; done

s "5. Network traffic over 10 s (bytes per second per interface)"
cat /proc/net/dev > /tmp/n1; sleep 10; cat /proc/net/dev > /tmp/n2
paste /tmp/n1 /tmp/n2 | awk 'NR>2{gsub(":","",$1); rx=($18-$2)/10; tx=($26-$10)/10; if(rx+tx>10000) printf "%-16s in %8.0f KB/s   out %8.0f KB/s\n",$1,rx/1024,tx/1024}'

s "6. Firewall size and the hourly reload"
firewall-cmd --get-ipsets 2>/dev/null
for i in $(firewall-cmd --get-ipsets 2>/dev/null); do echo "$i: $(firewall-cmd --ipset=$i --get-entries 2>/dev/null | wc -l) entries"; done
wc -l /etc/firewalld/block_ips.sh; grep -cE "^firewall-cmd|reload" /etc/firewalld/block_ips.sh
cat /proc/sys/net/netfilter/nf_conntrack_count /proc/sys/net/netfilter/nf_conntrack_max 2>/dev/null | paste -sd/ | xargs echo "tracked connections (used/max):"
journalctl -u firewalld --since "3 hours ago" --no-pager | tail -5

s "7. Load right now"; uptime; vmstat 2 5
echo; echo "Done: $(date)"
