const emailInput = document.querySelector('.email-input');
const errorMessageEmail = document.querySelector('.error-message-email');``
const getCodeBtn = document.querySelector('.get-code-button');
const verificationSection = document.querySelector('.verification-section');
const giveCodeSection = document.querySelector('.give-code-section');

function validateEmail(email) {
    if (!email.endsWith("@tpu.ru")){
        errorMessageEmail.style.display = "block";
    } else {
        verificationSection.style.display = "none";
        giveCodeSection.style.display = "block";
    }
    return true;
}

getCodeBtn.addEventListener("click", function () {
    if (validateEmail(emailInput.value)) {
        return true
    }
});



const codeInput = document.querySelector('.code-input');