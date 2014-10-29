
package ch.ethz.csg.oppnet.ui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.ListFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import ch.ethz.csg.oppnet.R;
import ch.ethz.csg.oppnet.core.Policy;
import ch.ethz.csg.oppnet.core.SupervisorService;
import ch.ethz.csg.oppnet.core.SupervisorService.SupervisorBinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

public class PolicyFragment extends ListFragment implements SupervisorService.Callback {
    private Policy mCurrentPolicy;
    private BroadcastReceiver mPolicyChangedReceiver;

    private PolicyListAdapter mListAdapter;
    private Button mApplyButton;
    private String[] mApplyButtonLabels;

    private ServiceConnection mSupervisorConnection;
    private SupervisorService mSupervisor;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Context context = getActivity();
        mCurrentPolicy = Policy.getCurrentPolicy(context);
        mPolicyChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCurrentPolicy = (Policy) intent.getSerializableExtra(Policy.EXTRA_NEW_POLICY);
                if (mSupervisor.isActivated()) {
                    toggleActiveFlag(true);
                }
            }
        };
        Policy.registerPolicyChangedReceiver(getActivity(), mPolicyChangedReceiver);

        mListAdapter = new PolicyListAdapter(context);
        setListAdapter(mListAdapter);

        mApplyButtonLabels = getResources().getStringArray(R.array.policy_button_labels);
    }

    @Override
    public void onStart() {
        super.onStart();

        mSupervisorConnection = new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mSupervisor = null;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                final SupervisorBinder binder = (SupervisorBinder) service;
                mSupervisor = binder.getSupervisor();
                mSupervisor.addCallback(PolicyFragment.this);
                onActivationStateChanged(mSupervisor.isActivated());
            }
        };
        SupervisorService.bindSupervisorService(getActivity(), mSupervisorConnection);
    }

    @Override
    public void onStop() {
        super.onStop();

        if (mSupervisor != null) {
            mSupervisor.removeCallback(this);
            getActivity().unbindService(mSupervisorConnection);
            mSupervisorConnection = null;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_policy_list, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        getListView().setItemChecked(mCurrentPolicy.ordinal(), true);

        mApplyButton = (Button) view.findViewById(R.id.button_policy_apply);
        mApplyButton.getBackground().setColorFilter(Color.GREEN, PorterDuff.Mode.MULTIPLY);
        mApplyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mSupervisor.changePolicy(getSelectedPolicy());

                if (!mSupervisor.isActivated()) {
                    // Not yet activated
                    mSupervisor.activateOppNet();
                }
            }
        });
    }

    public Policy getSelectedPolicy() {
        final int selectedPosition = getListView().getCheckedItemPosition();
        return mListAdapter.getItem(selectedPosition);
    }

    private void toggleActiveFlag(boolean show) {
        final ListView list = getListView();
        final int listCount = list.getChildCount();
        final int first = list.getFirstVisiblePosition();
        final int currentPolicyPosition = mCurrentPolicy.ordinal();

        PolicyViewHolder viewHolder;
        for (int i = 0; i < listCount; i++) {
            int visibilityState = View.INVISIBLE;
            if (show && (first + i == currentPolicyPosition)) {
                visibilityState = View.VISIBLE;
            }

            viewHolder = (PolicyViewHolder) list.getChildAt(i).getTag();
            viewHolder.activeLabel.setVisibility(visibilityState);
        }
    }

    @Override
    public void onActivationStateChanged(final boolean activated) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final int buttonLabelIndex = activated ? 1 : 0;
                mApplyButton.setText(mApplyButtonLabels[buttonLabelIndex].toUpperCase(Locale.US));

                toggleActiveFlag(activated);
            }
        });
    }

    public static class PolicyViewHolder {
        public PolicyViewHolder(View listItem) {
            title = (TextView) listItem.findViewById(R.id.policyTitle);
            description = (TextView) listItem.findViewById(R.id.policyDescription);
            activeLabel = (TextView) listItem.findViewById(R.id.policyActiveFlag);
        }

        public TextView title;
        public TextView description;
        public TextView activeLabel;
    }

    private static class PolicyListAdapter extends ArrayAdapter<Policy> {
        private final LayoutInflater mInflater;
        private final String[] mPolicyTitles;
        private final String[] mPolicyDescriptions;

        public PolicyListAdapter(Context context) {
            super(context, R.layout.fragment_policy_list,
                    new ArrayList<Policy>(Arrays.asList(Policy.values())));

            mInflater = LayoutInflater.from(context);

            Resources resources = context.getResources();
            mPolicyTitles = resources.getStringArray(R.array.policy_names);
            mPolicyDescriptions = resources.getStringArray(R.array.policy_descriptions);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            PolicyViewHolder viewHolder;

            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.list_item_policy, null);
                viewHolder = new PolicyViewHolder(convertView);
                convertView.setTag(viewHolder);
            } else {
                viewHolder = (PolicyViewHolder) convertView.getTag();
            }

            final ListView list = (ListView) parent;
            final CheckableRelativeLayout row = (CheckableRelativeLayout) convertView;
            final boolean isChecked = list.isItemChecked(position);

            row.setChecked(isChecked);
            viewHolder.title.setText(mPolicyTitles[position]);
            viewHolder.description.setText(mPolicyDescriptions[position]);

            return convertView;
        }
    }
}
