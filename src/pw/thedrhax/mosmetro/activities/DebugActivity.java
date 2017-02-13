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

package pw.thedrhax.mosmetro.activities;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.services.ConnectionService;
import pw.thedrhax.util.Logger;
import pw.thedrhax.util.Version;

public class DebugActivity extends Activity {
    // UI Elements
    private TextView text_messages;
    private Button button_connect;
    
    // Logger
    private Logger logger;
    private boolean show_debug = false;

    // Status variables
    private boolean service_running = false;

    // Receivers
    private BroadcastReceiver service_state;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.debug_activity);

        button_connect = (Button) findViewById(R.id.button_connect);
        service_state = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                service_running = intent.getBooleanExtra("RUNNING", false);
                button_connect.setText(service_running ?
                        getString(R.string.stop) : getString(R.string.retry)
                );
            }
        };
        registerReceiver(service_state,
                new IntentFilter("pw.thedrhax.mosmetro.event.ConnectionService")
        );

        // Get initial ConnectionService state (not very accurate)
        if (ConnectionService.isRunning()) {
            service_state.onReceive(this, new Intent().putExtra("RUNNING", true));
        }

        text_messages = (TextView)findViewById(R.id.text_messages);
        logger = Logger.getLogger().registerCallback(this, new Logger.Callback() {
            @Override
            public void log(Logger.LEVEL level, String message) {
                if (level != Logger.LEVEL.INFO || show_debug)
                    if (level != Logger.LEVEL.DEBUG || !show_debug)
                        return;
                text_messages.append(message + "\n");
            }
        });
        text_messages.setText("");

        // Check for log from ConnectionService
        if (getIntent() != null && "SHOW_LOG".equals(getIntent().getAction())) {
            for (Logger.LEVEL level : Logger.LEVEL.values()) {
                logger.getCallback(this).log(level, logger.get(level));
            }
            return;
        }

        button_connect(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logger.unregisterCallback(this);
        unregisterReceiver(service_state);
    }

    // ActionBar Menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.debug_activity, menu);

        // Show back button in menu
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_share:
                Intent send_email = new Intent(Intent.ACTION_SEND);

                send_email.setType("text/plain");
                send_email.putExtra(Intent.EXTRA_EMAIL, new String[] {getString(R.string.report_email_address)});
                send_email.putExtra(Intent.EXTRA_SUBJECT,
                        getString(R.string.report_email_subject, Version.getFormattedVersion())
                );
                send_email.putExtra(Intent.EXTRA_TEXT, logger.get(Logger.LEVEL.DEBUG));

                startActivity(Intent.createChooser(send_email, getString(R.string.report_choose_client)));
                return true;

            case android.R.id.home:
                finish();
                return true;

            case R.id.action_clear:
                logger.wipe();
                text_messages.setText("");
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void button_shortcut (View view) {
        startActivity(new Intent(this, ShortcutActivity.class));
    }

    public void button_connect (final View view) {
        Intent service = new Intent(this, ConnectionService.class);
        if (service_running)
            service.setAction("STOP");
        else
            service.putExtra("debug", true);
        startService(service);
    }

    // Handle debug log checkbox
    public void show_debug_log (View view) {
        show_debug = ((CheckBox)view).isChecked();
        text_messages.setText("");
        text_messages.append(logger.get(show_debug ? Logger.LEVEL.DEBUG : Logger.LEVEL.INFO));
    }
}
