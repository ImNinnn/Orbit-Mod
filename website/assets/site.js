// Mobile menu toggle. The site works without JavaScript; this only opens and closes the menu on small screens.
const toggle = document.querySelector(".nav-toggle");
const nav = document.getElementById("nav");
if (toggle && nav) {
	toggle.addEventListener("click", () => {
		const open = nav.classList.toggle("open");
		toggle.setAttribute("aria-expanded", String(open));
	});
	nav.addEventListener("click", (event) => {
		if (event.target instanceof HTMLAnchorElement) {
			nav.classList.remove("open");
			toggle.setAttribute("aria-expanded", "false");
		}
	});
}

// Download buttons: the newest Orbit release for each Minecraft version, from Orbit Cloud. The links in the page
// already point at a release, so they work without this (or if Orbit Cloud can't be reached).
const downloads = document.querySelectorAll("[data-target]");
if (downloads.length) {
	fetch("https://api.orbitcosmetics.dev/v1/mod/update?target=26.3&version=0.0.0")
		.then((response) => (response.ok ? response.json() : null))
		.then((body) => {
			const version = body && body.update && body.update.version;
			if (!version || !/^[0-9]+(\.[0-9]+)*$/.test(version)) {
				return;
			}
			downloads.forEach((link) => {
				link.href = `https://api.orbitcosmetics.dev/v1/mod/releases/${version}/${link.dataset.target}`;
			});
			document.querySelectorAll("[data-latest]").forEach((label) => {
				label.textContent = `Orbit ${version}`;
			});
		})
		.catch(() => {});
}
