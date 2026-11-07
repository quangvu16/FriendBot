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
import androidx.core.content.ContextCompat;
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

    // Styled like the Autopilot bottom sheet: white page, black text at the default size (14sp),
    // rows indented 16dp, openBotBlue accents and spinner_bg chips for actions.
    int blue = ContextCompat.getColor(requireContext(), R.color.openBotBlue);
    LinearLayout content = binding.guideContent;
    content.addView(text(getString(titleRes), 20, Typeface.BOLD, Color.BLACK, 16, 8));
    content.addView(text(getString(introRes), 14, Typeface.NORMAL, Color.BLACK, 16, 8));

    content.addView(header(getString(R.string.guide_section_materials), blue));
    for (String item : getResources().getStringArray(materialsRes)) {
      content.addView(text("•  " + item, 14, Typeface.NORMAL, Color.BLACK, 16, 4));
    }

    content.addView(header(getString(R.string.guide_section_steps), blue));
    String[] steps = getResources().getStringArray(stepsRes);
    for (int i = 0; i < steps.length; i++) {
      android.text.SpannableString line = new android.text.SpannableString((i + 1) + ".  " + steps[i]);
      int numEnd = String.valueOf(i + 1).length() + 1;
      line.setSpan(new android.text.style.ForegroundColorSpan(blue), 0, numEnd, 0);
      line.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, numEnd, 0);
      TextView tv = text("", 14, Typeface.NORMAL, Color.BLACK, 16, 4);
      tv.setText(line);
      content.addView(tv);
    }

    content.addView(header(getString(R.string.guide_section_tips), blue));
    for (String item : getResources().getStringArray(tipsRes)) {
      content.addView(text("•  " + item, 14, Typeface.NORMAL, Color.BLACK, 16, 4));
    }

    // "Open <mode>" chip, same spinner_bg chip as the Threads stepper on the Autopilot page.
    int modeDestination = modeDestination(guideId);
    TextView open = text("▶  " + getString(titleRes), 14, Typeface.BOLD, blue, 0, 0);
    open.setGravity(Gravity.CENTER);
    open.setBackgroundResource(R.drawable.spinner_bg);
    LinearLayout.LayoutParams openLp =
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
    openLp.setMargins(dp(16), dp(20), dp(16), dp(12));
    open.setLayoutParams(openLp);
    open.setOnClickListener(
        v -> {
          // Back to the mode page when we came from it, otherwise open it.
          androidx.navigation.NavController nav =
              androidx.navigation.Navigation.findNavController(requireView());
          if (!nav.popBackStack(modeDestination, false)) nav.navigate(modeDestination);
        });
    content.addView(open);

    content.addView(text(getString(R.string.guide_credit), 12, Typeface.ITALIC, Color.GRAY, 16, 0));
  }

  private static int modeDestination(String guideId) {
    switch (guideId) {
      case GUIDE_FACE:
        return R.id.followFaceFragment;
      case GUIDE_LINE:
        return R.id.followLineFragment;
      case GUIDE_AVOIDANCE:
        return R.id.avoidObjectFragment;
      case GUIDE_COLOUR:
      default:
        return R.id.followColourFragment;
    }
  }

  /** Section header: bold openBotBlue, padded like an Autopilot row label. */
  private TextView header(String value, int color) {
    TextView tv = text(value, 14, Typeface.BOLD, color, 16, 0);
    tv.setPadding(dp(16), dp(12), 0, dp(4));
    return tv;
  }

  private TextView text(String value, int sizeSp, int style, int color, int startDp, int bottomDp) {
    TextView tv = new TextView(requireContext());
    tv.setText(value);
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
    tv.setTypeface(Typeface.create(Typeface.DEFAULT, style), style);
    tv.setTextColor(color);
    tv.setGravity(Gravity.START);
    tv.setPadding(dp(startDp), 0, dp(16), dp(bottomDp));
    tv.setLayoutParams(
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    return tv;
  }

  private int dp(int value) {
    return (int) (value * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    binding = null;
  }
}
