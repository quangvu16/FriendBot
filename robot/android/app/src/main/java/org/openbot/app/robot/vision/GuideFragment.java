package org.openbot.app.robot.vision;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import org.openbot.app.robot.R;
import org.openbot.app.robot.databinding.FragmentGuideBinding;

/**
 * Scrollable instruction page for one of the vision modes. The content is adapted from the
 * tutorial pages of Kirstin813/L4-Individual-Project (coloured object, face, black line and
 * object avoidance stages). The guide id arrives as the {@code guideId} navigation argument.
 */
public class GuideFragment extends Fragment {

  public static final String ARG_GUIDE_ID = "guideId";
  public static final String GUIDE_COLOUR = "colour";
  public static final String GUIDE_FACE = "face";
  public static final String GUIDE_LINE = "line";
  public static final String GUIDE_AVOIDANCE = "avoidance";

  private FragmentGuideBinding binding;

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    binding = FragmentGuideBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    String guideId = getArguments() != null ? getArguments().getString(ARG_GUIDE_ID) : null;
    if (guideId == null) guideId = GUIDE_COLOUR;

    @StringRes int titleRes;
    @StringRes int introRes;
    @StringRes int materialsRes;
    @StringRes int stepsRes;
    @StringRes int tipsRes;
    switch (guideId) {
      case GUIDE_FACE:
        titleRes = R.string.tile_follow_face;
        introRes = R.string.guide_face_intro;
        materialsRes = R.array.guide_face_materials;
        stepsRes = R.array.guide_face_steps;
        tipsRes = R.array.guide_face_tips;
        break;
      case GUIDE_LINE:
        titleRes = R.string.tile_follow_line;
        introRes = R.string.guide_line_intro;
        materialsRes = R.array.guide_line_materials;
        stepsRes = R.array.guide_line_steps;
        tipsRes = R.array.guide_line_tips;
        break;
      case GUIDE_AVOIDANCE:
        titleRes = R.string.tile_object_avoidance;
        introRes = R.string.guide_avoidance_intro;
        materialsRes = R.array.guide_avoidance_materials;
        stepsRes = R.array.guide_avoidance_steps;
        tipsRes = R.array.guide_avoidance_tips;
        break;
      case GUIDE_COLOUR:
      default:
        titleRes = R.string.tile_follow_colour;
        introRes = R.string.guide_colour_intro;
        materialsRes = R.array.guide_colour_materials;
        stepsRes = R.array.guide_colour_steps;
        tipsRes = R.array.guide_colour_tips;
        break;
    }

    LinearLayout content = binding.guideContent;
    content.addView(text(getString(titleRes), 22, Typeface.BOLD, Color.BLACK));
    content.addView(spacer(12));
    content.addView(text(getString(introRes), 15, Typeface.NORMAL, Color.rgb(0x33, 0x33, 0x33)));
    content.addView(spacer(16));
    content.addView(text(getString(R.string.guide_section_materials), 17, Typeface.BOLD, Color.BLACK));
    content.addView(spacer(6));
    for (String item : getResources().getStringArray(materialsRes)) {
      content.addView(text("•  " + item, 15, Typeface.NORMAL, Color.rgb(0x33, 0x33, 0x33)));
      content.addView(spacer(4));
    }
    content.addView(spacer(12));
    content.addView(text(getString(R.string.guide_section_steps), 17, Typeface.BOLD, Color.BLACK));
    content.addView(spacer(6));
    String[] steps = getResources().getStringArray(stepsRes);
    for (int i = 0; i < steps.length; i++) {
      content.addView(text((i + 1) + ".  " + steps[i], 15, Typeface.NORMAL, Color.rgb(0x33, 0x33, 0x33)));
      content.addView(spacer(6));
    }
    content.addView(spacer(8));
    content.addView(text(getString(R.string.guide_section_tips), 17, Typeface.BOLD, Color.BLACK));
    content.addView(spacer(6));
    for (String item : getResources().getStringArray(tipsRes)) {
      content.addView(text("•  " + item, 15, Typeface.NORMAL, Color.rgb(0x33, 0x33, 0x33)));
      content.addView(spacer(4));
    }
    content.addView(spacer(16));
    content.addView(text(getString(R.string.guide_credit), 12, Typeface.ITALIC, Color.GRAY));
  }

  private TextView text(String value, int sizeSp, int style, int color) {
    TextView tv = new TextView(requireContext());
    tv.setText(value);
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
    tv.setTypeface(Typeface.create(Typeface.DEFAULT, style), style);
    tv.setTextColor(color);
    tv.setGravity(Gravity.START);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    tv.setLayoutParams(lp);
    return tv;
  }

  private View spacer(int heightDp) {
    View v = new View(requireContext());
    v.setLayoutParams(
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int) (heightDp * getResources().getDisplayMetrics().density)));
    return v;
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    binding = null;
  }
}
