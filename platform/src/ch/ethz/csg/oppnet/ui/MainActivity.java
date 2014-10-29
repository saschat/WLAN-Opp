
package ch.ethz.csg.oppnet.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.SimpleCursorAdapter;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBar.Tab;
import android.support.v7.app.ActionBarActivity;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import ch.ethz.csg.oppnet.R;
import ch.ethz.csg.oppnet.core.SupervisorService;
import ch.ethz.csg.oppnet.core.SupervisorService.SupervisorBinder;
import ch.ethz.csg.oppnet.data.DbHelper;
import ch.ethz.csg.oppnet.data.FullContract;
import ch.ethz.csg.oppnet.data.FullContract.NeighborProtocols;
import ch.ethz.csg.oppnet.data.FullContract.Packets;
import ch.ethz.csg.oppnet.exchange.ExchangePacketViewModel;
import ch.ethz.csg.oppnet.lib.data.Neighbor;
import ch.ethz.csg.oppnet.network.NetworkManager;

import org.jraf.android.backport.switchwidget.Switch;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends ActionBarActivity implements SupervisorService.Callback {
    private static enum FragmentContent {
        POLICY(null),
        NEIGHBORS(FullContract.NeighborProtocols.URI_ALL),
        APPS(FullContract.Apps.URI_ALL),
        PROTOCOLS(FullContract.Protocols.URI_ALL),
        PACKETS(FullContract.Packets.URI_ALL);

        public final Uri baseUri;

        private FragmentContent(Uri uri) {
            baseUri = uri;
        }
    }

    private SupervisorService mSupervisor;
    private ServiceConnection mSupervisorConnection;

    private ActionBar mActionBar;
    private Switch mMasterSwitch;
    private DataPagerAdapter mPagerAdapter;
    private ViewPager mViewPager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPagerAdapter = new DataPagerAdapter(getSupportFragmentManager());
        mViewPager = (ViewPager) findViewById(R.id.list_fragment_pager);
        mViewPager.setAdapter(mPagerAdapter);
        mViewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                getSupportActionBar().setSelectedNavigationItem(position);
            }
        });

        // Add tabs to action bar
        mActionBar = getSupportActionBar();
        mActionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        final ActionBar.TabListener tabListener = new ActionBar.TabListener() {
            @Override
            public void onTabSelected(Tab tab, FragmentTransaction ft) {
                mViewPager.setCurrentItem(tab.getPosition());
            }

            @Override
            public void onTabUnselected(Tab tab, FragmentTransaction ft) {
            }

            @Override
            public void onTabReselected(Tab tab, FragmentTransaction ft) {
                // previously selected, ignore
            }
        };

        for (final FragmentContent elem : FragmentContent.values()) {
            mActionBar.addTab(
                    mActionBar.newTab().setText(elem.toString()).setTabListener(tabListener));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Bind to supervisor
        mSupervisorConnection = new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mSupervisor = null;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                final SupervisorBinder binder = (SupervisorBinder) service;
                mSupervisor = binder.getSupervisor();
                mSupervisor.addCallback(MainActivity.this);

                if (mMasterSwitch != null) {
                    mMasterSwitch.setChecked(mSupervisor.isActivated());
                }
            }
        };
        bindService(new Intent(this, SupervisorService.class),
                mSupervisorConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Unbind from supervisor
        unbindService(mSupervisorConnection);
        mSupervisorConnection = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);

        mMasterSwitch =
                (Switch) MenuItemCompat.getActionView(menu.findItem(R.id.action_master_switch));
        mMasterSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    if (mActionBar.getSelectedNavigationIndex() == 0) {
                        PolicyFragment fragment = (PolicyFragment) mPagerAdapter.getCurrentItem();
                        mSupervisor.changePolicy(fragment.getSelectedPolicy());
                    }
                    mSupervisor.activateOppNet();
                } else {
                    mSupervisor.deactivateOppNet();
                }
            }
        });
        if (mSupervisor != null) {
            mMasterSwitch.setChecked(mSupervisor.isActivated());
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        switch (id) {
            case R.id.action_settings: {
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            }

            case R.id.action_reset_db : {
                DbHelper.resetDatabase(this);
                break;
            }

            default: {
                return false;
            }
        }

        // We handled it!
        return true;
    }

    @Override
    public void onActivationStateChanged(final boolean activated) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mMasterSwitch.setChecked(activated);
            }
        });
    }

    public static class DataPagerAdapter extends FragmentPagerAdapter {
        private Fragment mCurrentFragment;

        public DataPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public int getCount() {
            return FragmentContent.values().length;
        }

        @Override
        public Fragment getItem(int position) {
            if (position == 2 || position == 3) {
                return DataListFragment.newInstance(FragmentContent.values()[position]);
            } else if (position == 0) {
                return new PolicyFragment();
            } else {
                CursorBackedArrayListFragment<?> fragment;
                if (position == 1) {
                    fragment = new NeighborListFragment();
                } else {
                    fragment = new PacketListFragment();
                }

                Bundle args = new Bundle();
                args.putInt(CursorBackedArrayListFragment.KEY_LOADER_ID, position);
                fragment.setArguments(args);

                return fragment;
            }
        }

        @Override
        public void setPrimaryItem(ViewGroup container, int position, Object object) {
            mCurrentFragment = (Fragment) object;
            super.setPrimaryItem(container, position, object);
        }

        public Fragment getCurrentItem() {
            return mCurrentFragment;
        }
    }

    // FRAGMENTS
    public static class NeighborListFragment extends CursorBackedArrayListFragment<Neighbor> {
        @Override
        protected CursorBackedArrayAdapter<Neighbor> getCursorArrayAdapter(Context context) {
            return new CursorBackedArrayAdapter<Neighbor>(context) {
                @Override
                protected void objectsFromCursor(Cursor cursor, ArrayList<Neighbor> objects) {
                    while (cursor.moveToNext()) {
                        final Neighbor neighbor = Neighbor.fromCursor(cursor);
                        objects.add(neighbor);
                    }
                }

                @Override
                protected String getTitleText(Neighbor neighbor) {
                    return neighbor.getNodeIdAsHex().substring(0, 20).toLowerCase(Locale.US);
                }

                @Override
                protected String getDetailsText(Neighbor neighbor) {
                    final String network;
                    final String address;
                    if (neighbor.hasAnyIpAddress()) {
                        network = neighbor.getLastSeenNetwork();
                        address = neighbor.getAnyIpAddress().getHostAddress();
                    } else {
                        network = "bluetooth";
                        address = NetworkManager.unparseMacAddress(neighbor.getBluetoothAddress());
                    }
                    return String.format(Locale.US,
                            "Last seen %d seconds ago on network '%s' with address %s",
                            (System.currentTimeMillis() / 1000) - neighbor.getTimeLastSeen(),
                            network, address);
                }
            };
        }

        @Override
        protected String getEmptyString() {
            return "No neighbors";
        }

        @Override
        protected Uri getLoaderUri() {
            return NeighborProtocols.URI_CURRENT;
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setHasOptionsMenu(true);
        }

        @Override
        public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
            MenuItem item = menu.add("Show all neighbors");
            item.setCheckable(true);
            MenuItemCompat.setShowAsAction(item, MenuItemCompat.SHOW_AS_ACTION_NEVER);

            super.onCreateOptionsMenu(menu, inflater);
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            if (item.isCheckable()) {
                item.setChecked(!item.isChecked());
                return true;
            }
            return super.onOptionsItemSelected(item);
        }
    }

    public static class PacketListFragment extends
            CursorBackedArrayListFragment<ExchangePacketViewModel> {
        @Override
        protected CursorBackedArrayAdapter<ExchangePacketViewModel>
                getCursorArrayAdapter(Context context) {
            return new CursorBackedArrayAdapter<ExchangePacketViewModel>(context) {
                @Override
                protected void objectsFromCursor(Cursor cursor,
                        ArrayList<ExchangePacketViewModel> objects) {
                    while (cursor.moveToNext()) {
                        final ExchangePacketViewModel packet =
                                ExchangePacketViewModel.fromCursor(cursor);
                        objects.add(packet);
                    }
                }

                @Override
                protected String getTitleText(ExchangePacketViewModel item) {
                    // final StringBuilder sb = new StringBuilder("Packet ");
                    //
                    // if (item.getSenderNodeAsHex() != null) {
                    // sb.append("from: ");
                    // sb.append(item.getSenderNodeAsHex());
                    // if (item.getTargetNodeAsHex() != null) {
                    // sb.append(" / ");
                    // }
                    // }
                    //
                    // if (item.getTargetNodeAsHex() != null) {
                    // sb.append("to: ");
                    // sb.append(item.getTargetNodeAsHex());
                    // // sb.append();
                    // }
                    //
                    // return sb.toString();

                    return new String(item.getPayload());
                }

                @Override
                protected String getDetailsText(ExchangePacketViewModel item) {
                    final StringBuilder sb = new StringBuilder();

                    sb.append(TextUtils.join("|", item.getPacketQueues()));
                    sb.append(" on ");
                    sb.append(item.getProtocolAsHex().substring(0, 20).toLowerCase(Locale.US));
                    sb.append("…");

                    return sb.toString();
                }
            };
        }

        @Override
        protected String getEmptyString() {
            return "No packets";
        }

        @Override
        protected Uri getLoaderUri() {
            return Packets.URI_ALL;
        }
    }

    public static class DataListFragment extends ListFragment
            implements LoaderManager.LoaderCallbacks<Cursor> {
        private static final String ARG_CONTENT = "content";

        private FragmentContent mContent;
        private SimpleCursorAdapter mAdapter;

        public static DataListFragment newInstance(FragmentContent content) {
            DataListFragment f = new DataListFragment();

            Bundle args = new Bundle();
            args.putString(ARG_CONTENT, content.toString());
            f.setArguments(args);

            return f;
        }

        public static SimpleCursorAdapter newCursorAdapater(Context context, FragmentContent content) {
            int layout = android.R.layout.simple_list_item_1;
            int[] to = {
                    android.R.id.text1
            };

            String text1 = null;
            switch (content) {
                case APPS: {
                    text1 = FullContract.Apps.COLUMN_PACKAGE_NAME;
                    break;
                }

                case PROTOCOLS: {
                    text1 = FullContract.Protocols.COLUMN_IDENTIFIER;
                    break;
                }
            }

            String[] from = {
                    text1
            };
            return new SimpleCursorAdapter(context, layout, null, from, to, 0);
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            mContent = FragmentContent.valueOf(getArguments().getString(ARG_CONTENT));

            mAdapter = newCursorAdapater(getActivity(), mContent);
            setListAdapter(mAdapter);
        }

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            getLoaderManager().initLoader(mContent.ordinal(), null, this);
            setEmptyText("No " + mContent.toString().toLowerCase(Locale.US));
        }

        @Override
        public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
            MenuItem item = menu.add("Show all neighbors");
            item.setCheckable(true);
            MenuItemCompat.setShowAsAction(item, MenuItemCompat.SHOW_AS_ACTION_NEVER);
            super.onCreateOptionsMenu(menu, inflater);
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            if (item.isCheckable()) {
                item.setChecked(!item.isChecked());
                return true;
            }
            return super.onOptionsItemSelected(item);
        }

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            FragmentContent content = FragmentContent.values()[id];

            // Let the content provider apply the default projection, filter & sort order
            return new CursorLoader(getActivity(), content.baseUri, null, null, null, null);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            mAdapter.swapCursor(data);
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            mAdapter.swapCursor(null);
        }
    }
}
