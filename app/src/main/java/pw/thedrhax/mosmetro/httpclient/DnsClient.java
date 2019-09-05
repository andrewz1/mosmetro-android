/**
 * Wi-Fi в метро (pw.thedrhax.mosmetro, Moscow Wi-Fi autologin)
 * Copyright © 2015 Dmitry Karikh <the.dr.hax@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package pw.thedrhax.mosmetro.httpclient;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.List;

import okhttp3.Dns;
import pw.thedrhax.util.Logger;

public class DnsClient implements Dns {
    public static final DnsClient INSTANCE = new DnsClient();

    private SimpleResolver dns = null;

    public DnsClient(String hostname) {
        try {
            dns = new SimpleResolver(hostname);
            Logger.log(this, "Custom resolver initialized: " + dns.getAddress());
        } catch (UnknownHostException ex) {
            Logger.log(Logger.LEVEL.DEBUG, ex);
            Logger.log(this, "Unable to initialize custom resolver");
        }
    }

    public DnsClient() {
        this(null);
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (dns == null) {
            return Dns.SYSTEM.lookup(hostname);
        }

        SimpleResolver dns = new SimpleResolver();

        Lookup req;
        try {
            req = new Lookup(hostname, Type.A);
        } catch (TextParseException ex) {
            throw new UnknownHostException(hostname);
        }
        req.setResolver(dns);

        Record[] res = req.run();
        List<InetAddress> result = new LinkedList<>();
        for (Record record : res) {
            if (record instanceof ARecord) {
                result.add(((ARecord) record).getAddress());
            }
        }

        return result;
    }
}
