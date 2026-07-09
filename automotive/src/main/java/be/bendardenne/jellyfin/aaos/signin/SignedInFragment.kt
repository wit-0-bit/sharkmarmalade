package be.bendardenne.jellyfin.aaos.signin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import be.bendardenne.jellyfin.aaos.R

/**
 * Shown when the sign-in activity is opened while already signed in.
 *
 * This happens legitimately: the car's media host caches an auth-error card and fires its
 * resolution intent (this activity) even after a successful sign-in. Showing the blank server
 * form again reads as "the sign-in didn't take" — instead, state the situation and offer the two
 * things the user could actually want.
 */
class SignedInFragment : Fragment() {

    private lateinit var viewModel: SignInActivityViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.signed_in, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[SignInActivityViewModel::class.java]

        view.findViewById<TextView>(R.id.signed_in_label).text = getString(
            R.string.signed_in_as,
            viewModel.accountManager.server ?: "?",
            viewModel.accountManager.username ?: "?"
        )

        view.findViewById<Button>(R.id.signed_in_done_button).setOnClickListener {
            requireActivity().finish()
        }

        view.findViewById<Button>(R.id.sign_out_button).setOnClickListener {
            viewModel.signOut()
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.sign_in_container, ServerSignInFragment())
                .commit()
        }
    }
}
